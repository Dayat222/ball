#include "cheat.h"

namespace he {

void ReadQueue::every(Job job, int loMs, int hiMs) {
    jobs_.push_back(Entry{ std::move(job), loMs, hiMs, rnd(rng_, loMs, hiMs) });
}

// One pass in five performs no reads at all: an idle-inspection gap that keeps
// the read cadence from looking mechanical.
void ReadQueue::tick() {
    ++pass_;
    const bool skip = (pass_ % 5) == 0;
    for (auto& e : jobs_) {
        e.wait -= kPollMs;
        if (e.wait > 0) continue;
        e.wait = rnd(rng_, e.lo, e.hi);
        if (!skip) e.job();
    }
}

} // namespace he
