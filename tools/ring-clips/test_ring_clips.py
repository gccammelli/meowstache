"""python3 test_ring_clips.py"""
from datetime import datetime, timedelta, timezone
from meowstache_ring_clips import Budget, cat_periods, clip_name, wanted

utc = timezone.utc
t = lambda h, m, s=0: datetime(2026, 10, 16, h, m, s, tzinfo=utc)
assert clip_name(t(22, 32, 41)) == "2026-10-16T22-32-41Z.mp4"
assert clip_name(datetime(2026, 10, 16, 23, 32, 41, tzinfo=timezone(timedelta(hours=1)))) == "2026-10-16T22-32-41Z.mp4"

# The visit of 16 Oct: cat 22:32:49 -> 22:42 (status cd, cst, then cycling), still "cat" if the history ends mid-visit.
hist = [{"state": "rdy", "last_changed": "2026-10-16T22:15:00+00:00"}, {"state": "cd", "last_changed": "2026-10-16T22:32:49+00:00"},
        {"state": "cst", "last_changed": "2026-10-16T22:32:53+00:00"}, {"state": "ccp", "last_changed": "2026-10-16T22:42:13+00:00"}]
p = cat_periods(hist, ["cd", "cst"], t(23, 0))
assert p == [(t(22, 32, 49), t(22, 42, 13))], p
assert cat_periods(hist[:3], ["cd", "cst"], t(22, 35)) == [(t(22, 32, 49), t(22, 35))]
# The entrance recording starts just before the robot noticed; the second part during the visit; an unrelated one later.
assert wanted(t(22, 32, 35), 30, p, 90)
assert wanted(t(22, 33, 5), 30, p, 90)
assert not wanted(t(22, 50), 30, p, 90)
assert not wanted(t(22, 20), 30, p, 90)

b = Budget(3)
assert [b.take() for _ in range(4)] == [True, True, True, False]
print("ok")
