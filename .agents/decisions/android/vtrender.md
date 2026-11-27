# Terminal frames and rendering (android/vtrender) Decisions

> Snapshot of current consensus. Evolution: `git log --grep="MODULE: android/vtrender"`
>
> Last distilled: 2026-11-27 (HEAD = 2f321b12)
>
> Decisions made before android/vtrender was split from android/vtview retain their tags in `.agents/decisions/android/vtview.md`: D1–D3 and the rendering part of D5; earlier `android/terminal` decisions remain in `.agents/decisions/android/terminal.md`: D5, D9, D16, D17, D32 and the frame/rendering parts of D7, D30, D36, D41 and D46–D48.

## Active

### D1: Frame encoding, decoding and drawing share one module

- **What**: android/vtrender owns VtFrame, VtScreen, VtRenderer, VtImages, VtGlyphs, fonts and vt.c's frame specification/Frames section, with native ownership, JNI, PNG decoding and locking in android/vtcore.
- **Why**: The frame format and drawing pipeline have source boundaries distinct from view interaction.
- **Tradeoffs**: View orchestration remains in android/vtview and frame-buffer lifetime still crosses into native terminal state.
- **Watch out**: Keep native layout and Kotlin decoding aligned, consume frames on the main thread and preserve shared locking plus inherited rendering/device verification gaps.
- **Source**: 9015b244, 81f60bef; d053be24; 39e55b4b

### D2: Frame flag bit 7 reports pending output

- **What**: flags bit 7 encodes unsettled resize output or an active synchronized-output hold and is decoded as VtFrame.outputPending.
- **Why**: The view must distinguish resize-dirtied rows from settled program output.
- **Tradeoffs**: The final flag bit is consumed without changing the 64-byte header or JNI interface.
- **Watch out**: Keep native and Kotlin layouts aligned; rejected captures consume dirty rows and require another full frame.
- **Source**: 2e73a06a; 39e55b4b

### D3: Frame capture releases the terminal lock before encoding

- **What**: Snapshot render metadata and placements under render then terminal locks and encode rows after releasing the terminal lock.
- **Why**: Expensive frame encoding stalls the output pump.
- **Tradeoffs**: Two-phase capture adds a per-tab render lock (mode 2026 holds now freeze render state in place under D7 instead of rebuilding).
- **Watch out**: Preserve render-before-term-before-compression ordering and invalidate cached placements on state replacement.
- **Source**: acc5a7a0; 2f321b12

### D4: Concealed cells retain backgrounds and hide all foreground marks

- **What**: Use the same visibility predicate for glyphs, decorations and block-cursor text while retaining selection and search backgrounds.
- **Why**: Decorations could disclose concealed characters.
- **Tradeoffs**: Alternate-screen cursor defaults follow the underlying terminal implementation.
- **Watch out**: An alternate-screen settings change may reach the primary cursor only after reset.
- **Source**: 2852cdf9

### D5: Frame encoding batches libghostty-vt FFI reads and `VtRenderer` eliminates per-glyph allocations

- **What**: `vt.c` batches row/cell reads with `get_multi` calls, uses `row_iterator_next_dirty` when `!full && !marks_changed`, and skips full-grid search-mark work when no highlights exist, while Kotlin tracks `VtScreen.rowHasMarks`, reuses `CharArray(2)` and an unboxed open-addressed `GlyphWidthCache` (16,384 slots, cleared in `setFont`), batches `(0, midY)` canvas scaling in unified `drawRow`/`drawCursor`, and lifts row scratch fields onto `VtRenderer`.
- **Why**: Individual per-cell FFI getters, full-grid row scans, boxed width-cache maps, per-glyph `String`/`Ref` allocations, and per-glyph `Canvas.withScale` calls slowed incremental rendering.
- **Tradeoffs**: `vt.c` assumes `overscan.above == 0` for `row_iterator_next_dirty`'s `out_y`, and `VtRenderer` assumes single-threaded UI-thread drawing for its reusable scratch fields.
- **Watch out**: Adding above-viewport overscan would break `out_y` mapping, and any future unscaled drawing after `drawGlyph` in `VtRenderer` must call `endScale(c)` first.
- **Source**: a1fe8a19

### D6: Terminal rendering uses lazy per-row `RenderNode` display lists with immutable `VtImages` bitmaps

- **What**: `VtRenderer` maintains `rows + 1` per-row `RenderNode`s tracked by 3-state `RowDisplayState` (`EMPTY`/`DIRTY`/`CLEAN`), lazily recording visible dirty or discarded rows on hardware canvases via `translate(0, -top)` without per-row `defaultBg` fills, falling back to direct `drawRow` on software canvases, and `VtImages` allocates a fresh `ARGB_8888` bitmap per generation without manually recycling drawn bitmaps.
- **Why**: Full-screen software `ARGB_8888` backing bitmaps forced CPU rasterization and whole-screen GPU texture re-uploads on incremental frames, while in-place bitmap reuse/recycling was unsafe under asynchronous `RenderThread` display-list replay.
- **Tradeoffs**: Every view redraw (including cursor blinks) replays visible row display lists on the GPU instead of blitting a pre-rasterized texture, and animated Kitty images allocate a fresh bitmap per generation.
- **Watch out**: Real-device GPU replay cost for dense vector paths, box-drawing/CJK `wideScale` parity, and Kitty animation GC pressure remain unmeasured on Android hardware or an emulator, and `.agents/maps/android/vtrender.md` has exceeded 50% added lines since full verification `9015b244` and is due for a full map refresh.
- **Source**: e98ff62c

### D7: Mode 2026 synchronized-output holds freeze render state in place

- **What**: `appine_vt_frame_begin` skips `ghostty_render_state_begin_update` while `vt->held` is active so `render->state` stays frozen and terminal dirty flags survive for incremental updates after `CSI ? 2026 l`, setting `FrameSnap.skip_rows` (with default color fallback if uninitialized) when `render->state` dimensions do not yet match the terminal.
- **Why**: Eagerly capturing `vt->held_state` on `CSI ? 2026 h` consumed terminal dirty flags and forced a fresh `GhosttyRenderState` allocation and full-screen rebuild whenever a hold completed between frames.
- **Tradeoffs**: Ordinary output emitted right before a multi-frame hold remains hidden until the hold releases (at most 1 s), matching desktop Ghostty.
- **Watch out**: On-device `VtShellIntegrationTest` and `TerminalResizeTest` execution remains pending, and `.agents/maps/android/vtrender.md` remains due for a full map refresh.
- **Source**: 2f321b12

## Superseded

- ~~Mode 2026 `held_state` capture and full render-state rebuild (D3 in acc5a7a0)~~ → replaced by **D7** in 2f321b12 (2026-11-27)
