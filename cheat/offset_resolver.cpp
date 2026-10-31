#include "cheat.h"

namespace he {

OffsetResolver::OffsetResolver(HANDLE proc, uintptr_t moduleBase, std::vector<Chain> chains, uint64_t key)
    : proc_(proc), base_(moduleBase), key_(key), chains_(std::move(chains)) {
    cache_.assign(chains_.size(), 0);
}

bool OffsetResolver::resolve(size_t i, uintptr_t& out) const {
    if (i >= chains_.size()) return false;
    const Chain& c = chains_[i];
    if (c.len == 0 || c.len > kMaxChain) return false;

    uintptr_t p = base_ + dec_word(c.enc[0], key_);
    for (uint8_t k = 1; k < c.len; ++k) {
        uintptr_t next = 0;
        if (!rpm_t(proc_, p, next) || !next) return false;
        p = next + dec_word(c.enc[k], key_);
    }
    out = p;
    return true;
}

// Each pass re-walks every chain. A hop that fails drops the slot instead of
// keeping the previous pointer, so a stale address is never dereferenced.
void OffsetResolver::resolve_all() {
    for (size_t i = 0; i < chains_.size(); ++i) {
        uintptr_t p = 0;
        cache_[i] = resolve(i, p) ? p : 0;
    }
}

uintptr_t OffsetResolver::slot(size_t i) const { return i < cache_.size() ? cache_[i] : 0; }

void OffsetResolver::rotate(uint64_t key) {
    for (auto& c : chains_) {
        for (uint8_t k = 0; k < c.len; ++k) c.enc[k] = enc_word(dec_word(c.enc[k], key_), key);
    }
    key_ = key;
    resolve_all();
}

void OffsetResolver::tick() { ++frames_; }

bool OffsetResolver::stale() const { return (frames_ % kRedriveFrames) == 0; }

} // namespace he
