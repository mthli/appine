# android/vtrender Map

> Static understanding snapshot, not a decision history.
> See `.agents/decisions/android/vtrender.md` for the paired decision history (pre-split decisions remain D1–D3 and the rendering part of D5 in `.agents/decisions/android/vtview.md`, and D5, D9, D16, D17, D32 and the frame/rendering parts of D7, D30, D36, D41 and D46–D48 in `.agents/decisions/android/terminal.md`).
>
> Verified: 2026-11-27 (full mapping: 4 research concerns, 14 adversarial verification claims — 14 confirmed, 0 partial, 0 refuted, 2 new confirmed bugs)

## Responsibilities

`android/vtrender` defines, encodes, decodes and paints terminal frames: the little-endian native
frame wire format and Kitty image pixel extraction in `hosts/android/src/vt.c`, `VtFrame`'s
in-place decoder, `VtScreen`'s cell mirror, `VtRenderer`'s font metrics and per-row hardware
rendering, `VtImages`' bitmap cache, `VtGlyphs`' procedural shapes, and the bundled JetBrains Mono
variable fonts.

Root: `VtFrame.kt`, `VtScreen.kt`, `VtRenderer.kt`, `VtImages.kt` and `VtGlyphs.kt` in
`android/app/src/main/java/io/github/mthli/appine/`, `android/app/src/main/assets/fonts/`
(`JetBrainsMono.ttf`, `JetBrainsMono-Italic.ttf`), and the frame-format specification and Frames
section of `hosts/android/src/vt.c` (including frame-driven search catch-up, viewport match
marking, Kitty placement extraction and `appine_vt_image`).

Outside the module: `VtView` lifecycle, frame request orchestration, height-shrink preview,
redraw/blink/password/animation timers, input and search queries (`android/vtview`); touch/mouse
gestures, pixel scrolling, selection handles and overlays (`android/vtgestures`); terminal
creation, state ownership, Wuffs PNG decoding, mode 2026 callback ownership, cursor default
options, resize, scrollback compression, image trimming, JNI wrappers and tab locking
(`android/vtcore`). Where the flow below describes caller actions in `VtView` or `vt.rs`, those
responsibilities remain in their owning modules.

## Key types

- `AppineVtRender` and `FrameSnap` (`vt.c`) — per-tab native render state guarded by `Tab.render`
  separately from `Tab.term`: owns `GhosttyRenderState`, row/cell/image iterators, the output
  frame buffer, grapheme and row-extra scratch buffers, viewport search-mark buffers (`marks`,
  `marks_sent`, `marks_nonempty`, `marks_sent_nonempty`), cached sorted/merged Kitty `Placement`s
  with their placements generation, and `FrameSnap` (terminal-owned header fields snapshotted in
  phase 1).
- `VtFrame` (`VtFrame.kt`) — `@JvmInline value class` wrapping the direct `ByteBuffer` returned by
  `VtNative.frame` in little-endian byte order; decodes the 64-byte header, reads 56-byte image
  placements into pooled `VtImages.Placement`s, and unpacks rows into `VtScreen`.
- `VtScreen` (`VtScreen.kt`) — mirror of the last applied frame: flat `cols * (rows + 1)` cell
  arrays (`cp`, `fg`, `bg`, `attrs`, `clusters`) covering the viewport plus one overscan row below
  it, per-row selection column bounds (`selFrom`, `selTo`) and `rowHasMarks` flags, default
  colours, and cursor state.
- `VtRenderer` (`VtRenderer.kt`) — owns four variable-font `Paint`s (regular/bold × upright/italic),
  derived integer cell metrics, `wideScale`, `GlyphWidthCache`, `RowDisplayState`, per-row
  hardware `RenderNode`s (`rows + 1`, with software `Canvas` fallback), reusable row/glyph scratch
  fields, and drawing for rows, cursor/padlock and IME preedit; exports `WIDE`, `SEARCH_MATCH`,
  `SEARCH_SELECTED`, `drawsForeground`, `markOf`, `computeGlyphScale` and `encodeCodepoint`.
- `GlyphWidthCache` (`VtRenderer.kt`) — primitive open-addressed hash table (`IntArray` keys,
  `FloatArray` values, `Float.NaN` miss sentinel) caching single-codepoint advances keyed by
  `(paintIdx shl 21) or codepoint`, growing from 256 slots at 70% load up to 16,384 slots and
  clearing on overflow or font change.
- `RowDisplayState` (`VtRenderer.kt`) — 3-state (`EMPTY`, `DIRTY`, `CLEAN`) tracker for the
  `rows + 1` per-row display lists, governing lazy hardware recording, software fallback and
  overscan visibility.
- `VtImages` (`VtImages.kt`) — pools the last frame's `Placement` list across frames, caches
  immutable `ARGB_8888` bitmaps by `(imageId, generation)` (`Picture`), copies new generations via
  `VtNative.image`, draws layer-filtered placements intersecting a row, and drops entries without
  manual `recycle()` of potentially recorded bitmaps.
- `VtGlyphs` (`VtGlyphs.kt`) — procedural pixel-aligned renderer for 164 codepoints: box drawing
  (`U+2500–U+257F`), block elements (`U+2580–U+259F`) and Powerline triangles (`U+E0B0–U+E0B3`).

## Public entry points

- Native (`vt.c`, entered via `android/vtcore`'s `vt.rs`): `appine_vt_render_new`,
  `appine_vt_render_free`, `appine_vt_frame_begin` (called under `Tab.render` then `Tab.term`),
  `appine_vt_frame_end` (called under `Tab.render` alone) and `appine_vt_image` (called under
  `Tab.term` on locked `ARGB_8888` bitmap pixels from `VtNative.image`).
- `VtFrame`: header property accessors (`cols`, `rows`, `dirty`, `cursorShown`, `cursorStyle`,
  `cursorBlinking`, `cursorX`, `cursorY`, `defaultFg`, `defaultBg`, `cursorColor`, `rowCount`,
  `atBottom`, `mouseTracking`, `altScreen`, `altScroll`, `selection`, `rectangle`,
  `searchPending`, `outputPending`, `selStartX/Y`, `selEndX/Y`, `scrollTotal`, `scrollOffset`,
  `scrollLen`, `searchTotal`, `searchSelected`, `imageCount`, `overscan`, `pointerShape`,
  `animationMs`), `placement`, `takeRows`, and `DIRTY_NONE` / `DIRTY_FULL`.
- `VtScreen`: `resize`, `span`, cell/selection/mark arrays, default colours and cursor properties.
- `VtRenderer`: `setFont`, `cellWidth`, `cellHeight`, `baseline`, `resize`, `hasGrid`, `takeRows`,
  `invalidateRows`, `discardDisplayLists`, `drawGrid`, `drawCursor`, `advance`, `drawPreedit`, and
  companion helpers/constants.
- `VtImages`: `take`, `draw`, `drop`, `Placement`, and `LAYER_BELOW_BACKGROUND` /
  `LAYER_BELOW_TEXT` / `LAYER_ABOVE_TEXT`.
- `VtGlyphs`: `covers`, `draw`.

## Data flow / lifecycle

### Frame format and two-phase capture

- `VtView.onDraw` captures one frame per draw on the main thread via `VtNative.frame(tab, full)`.
  `vt.rs` clears `Tab.dirty`, acquires `Tab.render` then `Tab.term` for `appine_vt_frame_begin`,
  releases `Tab.term` so console output (`vt-pump`) can continue concurrently, runs
  `appine_vt_frame_end` under `Tab.render` alone, drops `Tab.render`, and wraps `render->frame.ptr`
  in a direct `ByteBuffer` valid until the next `VtNative.frame` call.
- Phase 1 (`appine_vt_frame_begin`): enforces the mode 2026 timeout and resize quiet-period check,
  either freezes `render->state` (when held) or runs `tick_animations` and
  `ghostty_render_state_begin_update`, refreshes `render->placed` via `images_changed` unless
  `skip_rows` (promoting `full = true` if placements changed while images were or are shown),
  advances any active search via `search_catch_up` (4 ms budget), fills `render->marks` via
  `mark_matches` unless `skip_rows`, and snapshots live terminal-owned metadata into `render->snap`
  (grid dimensions, `password_input`, `viewport_bottom`, mode/selection/search/pending flags,
  viewport selection endpoints via `read_viewport`, scrollbar triple, search counts, OSC 22
  pointer shape and `animation_ms`).
- Phase 2 (`appine_vt_frame_end`): when `skip_rows` is false, calls
  `ghostty_render_state_end_update` to complete deferred style denormalization, reads `dirty`
  (overridden to `DIRTY_FULL` when `snap->full`), default/cursor colours, cursor geometry and
  captured overscan, then writes the little-endian wire layout:
  - 64-byte header: `cols`, `rows`, `dirty` (0 none, 1 partial, 2 full), `cursor_visible`,
    `cursor_style` (`0` bar, `1` block, `2` underline, `3` hollow, `4` lock), `cursor_blinking`,
    `cursor_x/y`, `foreground`, `background`, `cursor_color` (`0xAARRGGBB`; 0 if no OSC 12),
    `row_count`, `viewport_bottom`, `flags` (bit 0 mouse tracking, 1 alt screen, 2 alt scroll 1007,
    3 focus events 1004, 4 selection exists, 5 rectangular, 6 search pending, 7 output pending),
    `selection_start_x/y` and `selection_end_x/y` (`0xffff` when absent or outside viewport),
    `scroll_total/offset/len`, `search_total/selected` (1-based from newest, 0 for none),
    `image_count` (clamped to `UINT16_MAX`), `overscan` (1 when `captured.below > 0`, else 0),
    `pointer` (`GhosttyMouseShape`, default text I-beam) and `animation_ms`.
  - `image_count` × 56-byte placements: `image_id`, `layer`, 3 reserved bytes, `generation`,
    signed `x`/`y` destination pixels from the grid corner, unsigned `width`/`height`, source rect
    (`source_x/y/width/height`) and `image_width`/`image_height`.
  - `row_count` rows: 12-byte header (`y`, `cell_count`, `extra_count`, inclusive
    `selection_from`/`selection_to` or `0xffff`), `cell_count` × 16-byte cells (`codepoint`, `fg`,
    `bg`, `attrs`, `extra`), and `extra_count` × `u32` trailing grapheme codepoints in cell order.
    `attrs` packs bold (0), italic (1), faint (2), blink (3), inverse (4), invisible (5),
    strikethrough (6), overline (7), 3-bit `GhosttySgrUnderline` (8–10; bit 11 unused), 2-bit
    `GhosttyCellWide` (12–13: narrow, wide, spacer tail, spacer head) and 2-bit search mark
    (14–15: none, match, selected).
- Row selection in `appine_vt_frame_end`:
  - When `dirty != DIRTY_FULL && !marks_changed`, uses
    `ghostty_render_state_row_iterator_next_dirty(render->rows, &y)` to skip clean rows inside
    libghostty-vt (valid because `overscan.above == 0`, so `y` equals viewport row with
    `y == snap->height` for the overscan row).
  - When `dirty == DIRTY_FULL || marks_changed`, walks every row with `row_iterator_next` and
    emits rows that are full, render-state dirty, or whose `render->marks` differ from
    `render->marks_sent` (viewport rows `y < snap->height` only). When `dirty == DIRTY_FALSE` and
    `marks_changed`, header byte 4 stays `0` while `row_count > 0` sends the mark-changed rows.
  - `put_row` batches row reads via `ghostty_render_state_row_get_multi` (`CELLS`, optional
    `SELECTION`) and cell reads via `ghostty_render_state_row_cells_get_multi` (`RAW`,
    `HAS_STYLING`, `GRAPHEMES_BUF` into a 16-codepoint stack buffer falling back to heap
    `render->grapheme` on `GHOSTTY_OUT_OF_SPACE`, optional `BG_COLOR`; plus `{STYLE, FG_COLOR}`
    when styled). On allocation failure, `appine_vt_frame_end` returns `NULL` before updating
    `marks_sent` or calling `ghostty_render_state_clean`, leaving state intact for retry.
- `VtFrame.takeRows` unpacks rows with `y <= screen.rows`, stores
  `selFrom[y] = if (to >= 0) from else -1` and `selTo[y] = to`, clears any trailing columns
  `x >= cell_count`, materializes `clusters[i]` only when `extra > 0`, computes
  `screen.rowHasMarks[y]` from the OR of the row's `attrs` bits 14–15, and invokes its callback so
  `VtRenderer.takeRows` marks each received row `DIRTY` in `RowDisplayState`.

### Mode 2026 synchronized-output holds and resize settling

- `on_render_hold` records `vt->held` and `vt->held_since` without touching `GhosttyRenderState`.
  In `vt.c`, `vt->held` is cleared only by `on_render_hold(..., false)` (e.g. `CSI ? 2026 l`),
  `appine_vt_reset`, or forcibly in `appine_vt_frame_begin` once more than 1 s (`HOLD_LIMIT_NS`)
  has elapsed since `held_since` (turning off `GHOSTTY_MODE_SYNCHRONIZED_OUTPUT` on `vt->term`).
  Neither `appine_vt_resize` nor `on_reset` (the program RIS callback) clears `vt->held`.
- While `vt->held` is true, `appine_vt_frame_begin` skips `tick_animations` and
  `ghostty_render_state_begin_update`, keeping `render->state` frozen and preserving terminal
  dirty flags so the first frame after `CSI ? 2026 l` updates incrementally rather than rebuilding
  `render->state`. If `render->state`'s dimensions do not match `(vt->cols, vt->height)` (e.g.
  after a resize or before the first frame), `skip_rows` is set: `appine_vt_frame_end` skips
  `end_update`, `clean`, placements, overscan and rows, emits `DIRTY_FALSE` with zeroed cursor
  (`cursorShown == false`, even under `password_input`), and preserves `render->state`'s colours
  when `state_rows > 0` or falls back to white on black.
- Resize settling (`FLAG_OUTPUT_PENDING`, header flags bit 7): `appine_vt_resize` sets
  `vt->resize_pending = true` and `vt->resize_output_at = 0` when `cols` or `rows` changes; each
  non-empty `appine_vt_feed` timestamps `resize_output_at`; `appine_vt_frame_begin` clears
  `resize_pending` once `!vt->held`, `resize_output_at != 0`, at least 32 ms (`RESIZE_QUIET_NS`)
  have elapsed and the parser is at `VT_GROUND`. Header bit 7 is set whenever `resize_pending ||
  held`. During `android/vtview`'s height-shrink preview, `VtView.apply` drops frames whose
  dimensions differ or whose `outputPending` bit is set (re-arming `full = true` because capturing
  consumed native dirty rows).

### Overscan and search progress

- `new_render_state` configures `overscan = {above = 0, below = 1}`. While scrolled back,
  libghostty-vt captures the row immediately below the viewport as row `y == rows` and header byte
  58 (`overscan`) is 1. That row carries cells, dirty state and selection bounds like viewport rows
  but no search marks (marks appear once it scrolls into the viewport). `VtView` shifts the drawn
  grid up by `scrollPx` (and zeroes `scrollPx` when `!frame.overscan` or viewport scrolling is
  inactive).
- Active searches are driven per frame in `appine_vt_frame_begin`: `search_catch_up` calls
  `ghostty_search_feed` and loops `ghostty_search_tick` for at most 4 ms (`SEARCH_BUDGET_NS`),
  setting `FLAG_SEARCH_PENDING` (bit 6) if work remains so `VtView` schedules another animation
  frame.
- `mark_matches` allocates `marks` and `marks_sent` (`cols * rows` bytes each) when grid size
  changes and uses `marks_nonempty` / `marks_sent_nonempty` to skip full-grid `memset`, `memcmp`
  and `memcpy` when no highlights are active or clearing. It fetches
  `GHOSTTY_SEARCH_DATA_VIEWPORT_MATCHES` and `GHOSTTY_SEARCH_DATA_SELECTED_MATCH`, converts
  endpoints to screen coordinates via `android/vtgestures`'s `viewport_ref` and `screen_point`
  (so matches starting above or ending below the viewport clip cleanly), and stamps `MARK_MATCH`
  then `MARK_SELECTED` (overwriting match) into `render->marks`. `VtView` reads `frame.dirty` only
  to throttle periodic `searchRows` scroll-bar refreshes (500 ms) while searching.

### Kitty graphics placements and image cache

- `tick_animations` calls `ghostty_kitty_graphics_animation_tick` before `begin_update` so due
  frames enter the current render state, returning the delay until the next frame clamped to at
  least 8 ms (`ANIMATION_MIN_MS`), or 0 when idle.
- `images_changed` skips work when `render->images_valid` and
  `GHOSTTY_RENDER_STATE_DATA_IMAGE_PLACEMENTS_GENERATION` is unchanged. Otherwise it reads
  17 placement fields via `ghostty_render_state_image_get_multi`, filters to
  `GHOSTTY_IMAGE_PROTOCOL_KITTY` placements whose image exists in `KITTY_GRAPHICS` with non-zero
  image and destination sizes, converts `(col, row, offset_x, offset_y)` into `i32`-clamped
  destination pixels, sorts by `(layer, z, id, placement_id, x, y)`, and runs `join_rows`:
  adjacent `VIRTUAL` placements with identical layer/z/id/generation/placement_id/x/width/source
  columns whose destinations touch vertically (`last.y + last.height == next.y`) and whose
  `source_y` meets within ±1 pixel of `last.source_y + last.source_height` are merged into one
  rectangle to eliminate row-by-row scaling seams. When placements changed while images were or are
  shown, `images_changed` returns `true` so `appine_vt_frame_begin` forces a full-row frame.
- `VtImages.take(tab, frame)` copies placements into a pooled `ArrayList<Placement>` (with
  `VtFrame.placement` clamping `dst`/`src` far edges to `Int.MAX_VALUE`). For each placement whose
  `(id, generation)` is not cached in `pictures`, `copy` allocates a fresh mutable `ARGB_8888`
  bitmap (never mutating a bitmap that could already be recorded into a `RenderNode` on
  `RenderThread`) and calls `VtNative.image` -> `appine_vt_image`. `appine_vt_image` verifies id,
  generation, dimensions, `stride >= width * 4` and complete pixel data, widens `RGB`/`GRAY_ALPHA`/
  `GRAY` and premultiplies non-opaque alpha `(c * a + 127) / 255` into locked bitmap pixels. If
  copying fails (image evicted, incomplete or newer generation), `VtImages` immediately recycles
  only that unrecorded bitmap and removes any stale map entry. Unreferenced ids are removed from
  `pictures` without `recycle()` so GC reclaims them after `RenderThread` finishes. If any new
  pixels arrived, `VtView.apply` calls `renderer.invalidateRows()`.
- On tab switch or window hide, `VtView.dropImages` stops animation callbacks, calls
  `images.drop()` and calls `renderer.discardDisplayLists()` so row `RenderNode`s drop bitmap
  references; the next visible frame's full placement list copies pixels back from libghostty-vt
  and re-records rows from `VtScreen`.

### Font metrics and glyph width caching

- `VtRenderer` loads `fonts/JetBrainsMono.ttf` and `fonts/JetBrainsMono-Italic.ttf` (JetBrains
  Mono 2.304 variable fonts) at `'wght' 400` and `'wght' 700` into four paints with
  `ANTI_ALIAS_FLAG | SUBPIXEL_TEXT_FLAG` and `'calt' 0, 'liga' 0` (ligatures off).
- `setFont(px, fillWide)` measures `"M"` at `px`, sets `cellWidth = max(1, round(advance))`, and
  rescales all four paints to `px * cellWidth / advance` so ASCII characters advance exactly one
  integer cell. `cellHeight = max(1, ceil(descent - ascent))`, `baseline` centres the ascent/descent
  box inside `cellHeight`, `light = max(1, round(underlineThickness))`, `underlineY` and `strikeY`
  follow `Paint` metrics (dotted `DashPathEffect` is `(light, light)`, dashed `(3*light, 2*light)`),
  and `wideScale = if (fillWide && ideograph > 0) max(1f, 2 * cellWidth / measureText("水")) else 1f`
  (1.2 with JetBrains Mono and a 1-em system CJK fallback). `setFont` clears `GlyphWidthCache`.
- `GlyphWidthCache` caches single-codepoint advances for non-plain glyphs by
  `((paintIdx and 3) shl 21) or (codepoint and 0x1FFFFF)` (all valid keys are non-negative,
  disjoint from `EMPTY = -1`). Capacity starts at 256, doubles at 70% occupancy up to 16,384
  slots, and clears on overflow rather than growing further.

### Per-row RenderNode display lists and row drawing

- `VtRenderer.resize(cols, rows)` returns `false` when `(cols, rows, gridWidth, gridHeight)` are
  unchanged and `rowNodes != null`. Otherwise it resizes `VtScreen`, resets `RowDisplayState` to
  `rows + 1` `EMPTY` slots, and allocates fresh `RenderNode("vt-row-$y")` instances positioned at
  `(0, top, cols * cellWidth, top + cellHeight)` with `clipToBounds = true`, leaving the previous
  array untouched so an active `VtView` hardware height-shrink preview `RenderNode` referencing
  those old nodes remains valid until cleared.
- `VtView.onDraw` fills the view with opaque `screen.defaultBg`, applies the frame, and calls
  `renderer.drawGrid(c, scrollPx)`. `drawGrid` clips to `cols * cellWidth × rows * cellHeight`
  (leaving any partial-row margin below in `defaultBg`), translates by `-scrollPx`, and iterates
  `0 until visibleRowCount(rows, scrollPx)` (`rows + 1` when `scrollPx > 0`, else `rows`),
  skipping `EMPTY` rows:
  - Hardware canvas: if `rowState.needsRecord(y, node.hasDisplayList())` (`DIRTY`, or `CLEAN`
    after `discardDisplayLists()` / OS eviction), `recordRow` translates the recording canvas by
    `-top` and runs `drawRowContent`, then marks `CLEAN`; replays `c.drawRenderNode(node)`.
  - Software canvas: runs `drawRow` directly under a row clip without marking `CLEAN`.
- `drawRowContent` paints each row in strict z-order over the canvas's opaque `defaultBg` (no
  per-row `defaultBg` fill):
  1. `LAYER_BELOW_BACKGROUND` images (visible through default-background cells).
  2. Coalesced non-default background runs from `bgOf` (search match `0xFFFFE082` and selected
     match `0xFFF2A57E` override cell background; SGR inverse swaps fg/bg).
  3. `LAYER_BELOW_TEXT` images.
  4. Translucent selection highlight (`0x604A90E2`) from `selFrom[y]` to
     `min(selTo[y] + span(selTo[y]), cols)`.
  5. Foreground text and decorations: skips `SPACER_TAIL` and `SPACER_HEAD`; SGR 8 (`INVISIBLE`)
     hides both glyph and all decorations (`drawsForeground`), leaving cell/search/selection
     backgrounds intact. Plain narrow ASCII cells (`cluster == null && wide == NARROW &&
     (code == 0 || code in 0x20..0x7e)`) batch by `(paint, color, decor)` up to 4,096 chars into
     one `drawText` call, skipping isolated undecorated blanks and trimming trailing undecorated
     spaces. Other cells route through `drawGlyph`:
     - Kitty placeholder cells (`U+10EEEE`, with or without combining diacritics) draw nothing.
     - Single codepoints covered by `VtGlyphs` draw pixel-aligned shapes across `span * cellWidth`
       (box-drawing arms, dashes, anti-aliased rounded corners and diagonals; block halves,
       eighths, quadrants and 64/128/192-alpha shades; filled and stroked Powerline triangles).
     - Remaining single codepoints encode into a reusable `CharArray(2)` (`encodeCodepoint`) and
       query `GlyphWidthCache`; multi-codepoint clusters measure and draw their `String`.
     - `computeGlyphScale` scales wide cells by `wideScale` and shrinks any glyph exceeding
       `w + 0.5f` evenly to `w / advance` (never stretched). Consecutive glyphs with the same scale
       in a row share one `Canvas.scale(s, s, 0f, top + cellHeight / 2f)` transform drawn at
       `x = l / s`; `endScale(c)` restores before plain runs, `VtGlyphs`, decorations or row exit.
     - Foreground colour (`fgOf`) uses black (`0xFF000000`) on search matches, applies inverse, and
       applies faint (`FAINT`) as alpha `0x80`. `markOf` short-circuits when `!rowHasMarks[y]` and
       lets `SPACER_TAIL` (`col > 0`) inherit the preceding wide cell's search mark.
     - `decorate` draws single, double, curly (quadratic wave path), dotted and dashed underlines,
       strikethrough and overline. Text blink (attrs bit 3) is ignored.
  6. `LAYER_ABOVE_TEXT` images.
- Default foreground/background changes (OSC 10/11, reverse video) and newly arrived image pixels
  trigger `VtRenderer.invalidateRows()` (`CLEAN -> DIRTY`, preserving `EMPTY` rows) so unaffected
  rows re-record on the same frame.

### Cursor, password lock and IME preedit

- After `drawGrid`, `VtView` translates by `-scrollPx` and draws the cursor (unless hidden by
  blink phase) and IME preedit on top of the grid each frame.
- `VtRenderer.drawCursor(c, focused, composing)` returns if `!cursorShown` or out of bounds,
  resolves colour as OSC 12 `cursorColor` else `defaultFg`, and renders:
  - `CURSOR_LOCK` (style 4, when `!composing`): a vector padlock standing on the baseline, centred
    across `span` cells (about capital height, never blinking, shown unfocused too). While
    `composing`, falls through to block/hollow like Ghostty.
  - `CURSOR_BAR`: `2 * light` wide at the cell's left edge (single-cell width even on wide cells).
  - `CURSOR_UNDERLINE`: `2 * light` tall along the bottom across `span` cells.
  - `CURSOR_HOLLOW`, or unfocused `CURSOR_BLOCK` / composing `CURSOR_LOCK`: `light`-wide stroked
    rectangle across `span` cells. Note `CURSOR_BAR` and `CURSOR_UNDERLINE` do not become hollow
    when unfocused.
  - Focused `CURSOR_BLOCK` / composing `CURSOR_LOCK`: filled rectangle clipped to the cell with
    the underlying glyph redrawn (when `drawsForeground`) in `bgOf(...)` via `drawGlyph` +
    `endScale`.
- Password lock source: `appine_vt_frame_end` emits style `CURSOR_LOCK`, `cursor_blinking = 0` and
  `cursor_visible = cursor.viewport_has_value` (ignoring program cursor-hide) whenever
  `snap->password_input` is set (updated by `android/vtcore`'s pump and polled every 200 ms by
  `VtView` while visible).
- `VtRenderer.drawPreedit(c, text)` draws the IME's composing text at `(cursorX, cursorY)` using
  regular paint (`paints[0]`), shifted left so `l + w <= cols * cellWidth` (clamped at `>= 0`),
  over an opaque `defaultBg` rectangle with `defaultFg` text and a `light`-thick underline.
  `VtRenderer.advance(text)` exposes regular-paint width for `VtView`'s `CursorAnchorInfo`.

## Dependencies (inbound / outbound)

### Inbound

- `android/vtview` (`VtView`): requests frames (`VtNative.frame`), applies headers to `VtScreen`
  and view state, manages `full`-frame requests, font/wide-cell settings, height-shrink preview,
  blink/password/animation timers, `VtImages.take`/`drop`, `VtRenderer.resize`/`takeRows`/
  `invalidateRows`/`discardDisplayLists`/`drawGrid`/`drawCursor`/`advance`/`drawPreedit`, and
  reads `VtScreen.selFrom`/`selTo`/`span` and `VtRenderer.SEARCH_MATCH`/`SEARCH_SELECTED` for
  overlays (selection handles, floating menu rect, scroll-bar match marks).
- `android/vtcore` (`vt.rs`): creates/destroys `AppineVtRender`, invokes `appine_vt_frame_begin`
  and `appine_vt_frame_end` under `Tab.render` / `Tab.term`, exposes the frame `ByteBuffer`, and
  locks Android bitmaps via `libjnigraphics` for `appine_vt_image`.

### Outbound

- `android/vtcore`: `AppineVt` terminal ownership and locking, `VtNative.frame`/`image` JNI
  bridges, `VtNative.CURSOR_*` constants, Wuffs PNG decoding (`decode_png`), Kitty storage limits
  (32 MiB/screen; trimmed to 4 MiB on background tabs via `VtNative.dropImages`), cursor defaults
  (`appine_vt_set_cursor`), `on_render_hold` and `resize_pending` updates.
- `android/vtgestures`: `vt.c`'s Selection helpers `viewport_ref` and `screen_point`, called by
  `mark_matches` and `mark_match` to convert viewport/match grid refs into screen coordinates.
- `android/vtview`: search lifecycle (`vt->search`, `appine_vt_search*`) whose progress and matches
  are advanced and marked during `appine_vt_frame_begin`.
- `libghostty-vt` (1.0.0): `GhosttyRenderState`, `overscan`, batched `get_multi` row/cell/image
  APIs, `row_iterator_next_dirty`, Kitty graphics placements and animation ticking, and
  `GhosttySearch` feed/tick/viewport-match APIs.
- Android graphics & fonts: `RenderNode`, `Canvas`, `Paint`, `Bitmap`, `Typeface.Builder`
  (`JetBrainsMono.ttf` / `JetBrainsMono-Italic.ttf` 2.304, with system fallback such as Noto Sans
  CJK for non-Latin glyphs).

## Invariants and gotchas

- **Locking and buffer lifetime**: Capture and consume frames on the main thread only. `vt.rs`
  always acquires `Tab.render` before `Tab.term`, releases `Tab.term` before `appine_vt_frame_end`,
  and returns a direct `ByteBuffer` aliasing `render->frame.ptr` that stays valid until the next
  `VtNative.frame` call (read after unlocking). `VtRenderer`'s row scratch fields (`runStart`,
  `runChars`, `glyphChars`, `activeScale`, etc.) assume single-threaded UI-thread drawing.
- **Wire-format coupling**: `vt.c` and `VtFrame.kt` share exact byte offsets, bit positions and
  little-endian layout and must change together. `vt.c` writes `FLAG_FOCUS_EVENTS` (bit 3), which
  `VtFrame.kt` does not decode.
- **`overscan.above == 0` assumption**: `appine_vt_frame_end` maps `row_iterator_next_dirty`'s
  `out_y` directly to viewport row `y` (with `y == height` for the overscan row below). Enabling
  above-viewport overscan would shift every `out_y` and break row indexing and mark lookup.
- **Batched canvas scale contract**: `drawGlyph` leaves `activeScale != 1f` active across
  consecutive same-scale glyphs in a row, assuming constant `midY = top + cellHeight / 2f` within
  one `drawRowContent` or `drawCursor` call. Any unscaled drawing after `drawGlyph` (or leaving the
  row/clip) MUST call `endScale(c)` first.
- **RenderNode and Bitmap safety**: Never overwrite a drawn `VtImages` bitmap in place via JNI and
  never call `recycle()` on a bitmap once it may have been recorded into a `RenderNode` (only
  unrecorded failed copies are recycled immediately). Because images are recorded into per-row
  `RenderNode`s, any placement list change must force a full frame (`vt.c`), newly arrived pixels
  must call `VtRenderer.invalidateRows()`, and tab switch / window hide must pair `VtImages.drop()`
  with `VtRenderer.discardDisplayLists()` so row nodes release bitmap references.
- **Resize preview RenderNode retention**: `VtRenderer.resize` allocates a fresh `rowNodes` array
  instead of calling `discardDisplayList()` on the old nodes, because `VtView`'s hardware
  height-shrink preview `RenderNode` may still reference the old row nodes until cleared.
- **Integer cell-width snapping on font changes**: `setFont` rescales `textSize` to
  `px * cellWidth / advance`, so small font-size changes whose rounded `cellWidth` and `cellHeight`
  do not change leave `gridWidth`/`gridHeight` equal; `VtRenderer.resize` returns `false` and
  `regrid` performs no redraw.
- **Virtual placement seam joining**: libghostty-vt emits virtual Kitty placements one row at a
  time with integer-rounded source rects; `vt.c`'s `join_rows` merges contiguous rows within ±1 px
  source rounding so they scale uniformly. Rows partly scrolled off the top retain the rounding on
  their visible top edge.
- **Per-screen cursor shapes in libghostty-vt 1.0.0**: Concrete cursor shape is stored per screen,
  while blink mode and the default-vs-explicit flag are terminal-wide. Mode 1049 copies on entry
  and restores a saved cursor without shape on exit (leaving the primary shape intact), whereas
  modes 47/1047 copy in both directions. Settings updates stored defaults immediately only for the
  active screen while following defaults; under explicit DECSCUSR appearance, both defaults wait
  for DECSCUSR 0, DECSTR or RIS.
- **Memory budget for Kitty images**: Each screen of each tab retains up to 32 MiB of decoded
  Kitty pixels in libghostty-vt (`KITTY_STORAGE`), and the foreground tab holds a second GPU-backed
  bitmap copy in `VtImages` for placed images until tab switch or window hide; `android/service`
  trims background tabs to 4 MiB/screen via `VtNative.dropImages`. Snapshots encode images when
  below `SNAPSHOT_MAX` (16 MiB), whereas transcript fallbacks retain text only.

## Confirmed bugs / technical debt

- **Alternate-screen cursor Settings leak**: Changing cursor Settings while on the alternate
  screen can leave the primary screen showing its previous default shape after mode 1049 exits,
  because libghostty-vt's C API exposes neither an inactive-screen shape setter nor per-screen
  default ownership, and reapplying defaults on exit could overwrite a program-selected primary
  shape.
- **Shade blocks ignore SGR 2 (`FAINT`) alpha**: `VtGlyphs.block` sets `fill.alpha = (cp - 0x2590) * 64`
  for `U+2591–U+2593`, overwriting the `0x80` alpha set by `VtRenderer.fgOf` for faint cells rather
  than modulating it.
- **Live search marks during a mode 2026 hold**: When `vt->held` is true and `!skip_rows`,
  `appine_vt_frame_begin` freezes `render->state` at pre-hold content while still running
  `search_catch_up` and `mark_matches` against live `vt->term`/`vt->search`. If the held output
  shifted matches, `appine_vt_frame_end` can re-encode frozen pre-hold rows stamped with post-hold
  search mark positions.
- **Unused libghostty-vt drawing features**: default palette customization (colour scheme reply is
  fixed to dark), above-viewport overscan, and colour utilities (`palette_generate`, contrast and
  luminance).

## Open questions

- Should a Nerd Font or a CJK fallback font be bundled?
- When `vt->held && skip_rows` occurs without an active `resizePreview` (e.g. a widening resize or
  a new tab entering mode 2026 before its first frame), `VtView.apply` accepts the zero-row,
  zero-image, hidden-cursor frame (`hasFrame = true`), drops cached `VtImages` until the hold ends,
  and leaves a subsequent height-shrink during the same hold able to capture a blank
  `resizePreview` — is that intended or should `VtView` retain prior images/cursor on `skip_rows`
  frames?

## To verify

- Whether `libghostty-vt` 1.0.0 itself invokes `on_render_hold(..., false)` on a program-initiated
  RIS (`vt.c`'s `on_reset` callback does not clear `vt->held`; libghostty-vt source is outside this
  repository).
- On-device / emulator coverage gaps: earlier manual checks ran only on a Pixel 4a API 34 emulator
  (4 KB pages, Gboard English, adb/`sendevent`/`uinput` mouse) and macOS C harnesses; not yet
  verified on real Android hardware, 16 KB-page devices, or Android 11–13:
  - Per-row `RenderNode` hardware rendering, GPU replay cost for dense vector paths, box-drawing
    and CJK `wideScale` parity, hardware height-shrink preview, and on-device execution of
    `TerminalResizeTest.hardwareRecordingPopulatesAndRecoversAfterDiscard` and
    `VtShellIntegrationTest.twoPhaseFrameCaptureHandlesSyncHoldAndConcurrentOutput`.
  - Kitty graphics end-to-end (yazi, timg, `kitten icat`, tmux passthrough, 32 MiB eviction, and
    GC pressure under animated Kitty images allocating fresh bitmaps per generation).
  - Cursor Settings in non-English locales or while a full-screen program is active; padlock cursor
    with third-party IMEs composing at it; search marks while rows leave a full scrollback.
