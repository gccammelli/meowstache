# Photo URL regression

These are actual Robolectric native Compose renders of the shared `NetImage` component. The test first loads Mochi, then changes the URL to Luna. The before image retains Mochi because the producer kept its old bitmap and skipped the new download; the after image correctly shows Luna.

| Before | After |
| --- | --- |
| ![Old photo remains after the URL changes](avatar-before.png) | ![New photo loads after the URL changes](avatar-after.png) |

`UiKitTest.loadedAvatarsAreReusedAndThePhotoCacheStaysBounded` also checks cache reuse, eviction and the 24-image limit. Run the test with `MEOWSTACHE_AVATAR_EVIDENCE` set to an output PNG path to export the frame immediately after the first URL change. The before image was captured with only the producer's new value reset removed; that run failed the regression assertion. The fixed run passes.

These renders use bundled demo photos and a mocked HTTP transport. They do not claim a physical phone test.
