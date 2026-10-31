// Build-time post-pass: renames code sections, appends dead overlay padding.
// Entry-point obfuscation and delayed imports are link-time; it prints the flags.
#include <windows.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <random>
#include <vector>

static std::vector<BYTE> slurp(const wchar_t* path) {
    std::vector<BYTE> out;
    FILE* f = nullptr;
    if (_wfopen_s(&f, path, L"rb") || !f) return out;
    _fseeki64(f, 0, SEEK_END);
    const long long n = _ftelli64(f);
    _fseeki64(f, 0, SEEK_SET);
    if (n > 0) {
        out.resize(size_t(n));
        if (fread(out.data(), 1, out.size(), f) != out.size()) out.clear();
    }
    fclose(f);
    return out;
}

static bool spill(const wchar_t* path, const std::vector<BYTE>& data) {
    FILE* f = nullptr;
    if (_wfopen_s(&f, path, L"wb") || !f) return false;
    const bool ok = fwrite(data.data(), 1, data.size(), f) == data.size();
    fclose(f);
    return ok;
}

static bool taken(BYTE* secBase, WORD count, WORD skip, const char* name) {
    for (WORD j = 0; j < count; ++j) {
        if (j == skip) continue;
        auto* o = reinterpret_cast<IMAGE_SECTION_HEADER*>(secBase) + j;
        if (!strncmp(reinterpret_cast<const char*>(o->Name), name, 7)) return true;
    }
    return false;
}

int wmain(int argc, wchar_t** argv) {
    if (argc < 3) {
        std::fwprintf(stderr, L"usage: peh <in.exe> <out.exe> [padKB]\n");
        return 1;
    }
    const int padKB = argc > 3 ? _wtoi(argv[3]) : 64;

    std::vector<BYTE> file = slurp(argv[1]);
    if (file.size() < 0x200) return 1;

    auto* dos = reinterpret_cast<IMAGE_DOS_HEADER*>(file.data());
    if (dos->e_magic != IMAGE_DOS_SIGNATURE || size_t(dos->e_lfanew) + 0x100 > file.size()) return 1;

    auto* nt = reinterpret_cast<IMAGE_NT_HEADERS*>(file.data() + dos->e_lfanew);
    if (nt->Signature != IMAGE_NT_SIGNATURE) return 1;
    const bool pe32plus = nt->OptionalHeader.Magic == IMAGE_NT_OPTIONAL_HDR64_MAGIC;

    BYTE* secBase = reinterpret_cast<BYTE*>(&nt->OptionalHeader) + nt->FileHeader.SizeOfOptionalHeader;
    const WORD count = nt->FileHeader.NumberOfSections;
    if (secBase + size_t(count) * sizeof(IMAGE_SECTION_HEADER) > file.data() + file.size()) return 1;

    static const char* kPool[] = { ".rsrc", ".data", ".rodata", ".bss", ".reloc", ".sdata", ".xdata" };
    constexpr int kPoolN = int(sizeof(kPool) / sizeof(kPool[0]));

    int renamed = 0;
    for (WORD i = 0; i < count; ++i) {
        auto* s = reinterpret_cast<IMAGE_SECTION_HEADER*>(secBase) + i;
        if (!(s->Characteristics & IMAGE_SCN_CNT_CODE)) continue;

        const char* pick = nullptr;
        for (int k = 0; k < kPoolN && !pick; ++k) {
            if (!taken(secBase, count, i, kPool[k])) pick = kPool[k];
        }
        if (!pick) continue;

        char name[8]{};
        strncpy_s(name, sizeof(name), pick, 7);
        memcpy(s->Name, name, 8);
        ++renamed;
    }

    std::mt19937 rng{ std::random_device{}() };
    const size_t pad = size_t(padKB > 0 ? padKB : 0) * 1024;
    if (pad) {
        const size_t at = file.size();
        file.resize(at + pad);
        for (size_t i = at; i < file.size(); ++i) file[i] = BYTE(rng() & 0xFF);
    }

    if (!spill(argv[2], file)) return 1;

    std::wprintf(L"peh: %d section(s) renamed, %d KB overlay padding, pe32plus=%d\n",
                 renamed, padKB, pe32plus ? 1 : 0);
    // Dead code padding: /Gw + a 50-100KB referenced-but-unreachable blob, or
    // the overlay above. Delayed imports: /DELAYLOAD:user32.dll /DELAYLOAD:gdi32.dll
    // delayimp.lib. Entry shim: /ENTRY:entry_shim, keep the CRT entry call inside it.
    return 0;
}
