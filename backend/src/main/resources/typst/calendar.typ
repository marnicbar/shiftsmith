// calendar.typ — the ShiftSmith calendar PDF template.
//
// It is a *pure renderer*: everything it draws comes from `data.json`, which the
// backend writes next to this file into a scratch directory before invoking
// `typst compile`. No text is composed here — labels, dates, times and names are
// already localised and formatted by CalendarDocumentBuilder, so the PDF matches
// what the user sees on screen. (Your editor will flag `data.json` as missing: it
// only exists in that scratch directory at render time.)
//
// `logo.svg` — the ShiftSmith mark printed beside the brand name in the page
// footer — is copied into the same scratch directory by PdfExportService, from
// `typst/logo.svg` on the classpath. It is the same drawing the web app uses
// (`frontend/public/logo.svg`); keep the two copies in lock-step.
//
// Deliberately self-contained: no `@preview` packages, because the production
// container has no network access at render time.
//
// A document is a list of sections, one page each — a single export is a one-section
// document, and a batch export is the same code with a longer list. Each page body is
// one `height: 100%` block holding a three-row grid (header / 1fr body / footer band).
// The `1fr` row is what makes the calendar stretch to exactly one page: `layout()`
// inside it reports the leftover height, which becomes the pixels-per-hour scale.

#let doc = json("data.json")
#let meta = doc.meta
#let labels = doc.labels

// --- colours ----------------------------------------------------------------
// The app's palette is OKLCH (see backend Palette / frontend theme.js); colours
// arrive as {l, c, h} components so both renderers derive the same swatch.
#let swatch(c) = if c == none { luma(120) } else { oklch(c.l * 100%, c.c, c.h * 1deg) }

#let ink = luma(20)
#let ink-soft = luma(105)
#let hairline = luma(205)
#let rule-strong = luma(150)
#let dim-fill = luma(248)

// Width of the hour-label gutter down the left of a day/week grid. The calendar
// proper starts here, so anything that belongs to the calendar — including the rule
// closing it off at the bottom — is indented by it and never runs under the labels.
#let gutter = 13mm

// --- page -------------------------------------------------------------------
#set page(
  paper: meta.paper,
  flipped: meta.orientation == "landscape",
  margin: (x: 12mm, top: 10mm, bottom: 12mm),
  footer: context [
    #set text(size: 7.5pt, fill: ink-soft)
    #grid(
      columns: (1fr, auto, 1fr),
      align: (left, center, right),
      meta.generated,
      [#counter(page).display("1") / #counter(page).final().first()],
      grid(
        columns: 2,
        column-gutter: 0.5em,
        align: horizon,
        image("logo.svg", height: 1.2em), [#meta.brand],
      ),
    )
  ],
)

// DejaVu Sans ships in the container image; the rest are fallbacks so the template
// still renders on a developer machine (Typst's own embedded serif is the last resort).
#set text(font: ("DejaVu Sans", "Liberation Sans", "Libertinus Serif"), size: 9pt, fill: ink)
#set par(leading: 0.45em)

// --- header -----------------------------------------------------------------

#let section-header(sec) = {
  block(width: 100%, below: 0pt, {
    grid(
      columns: (1fr, auto),
      align: (left + bottom, right + bottom),
      {
        text(size: 15pt, weight: 700)[#sec.title]
        if sec.subtitle != "" {
          linebreak()
          text(size: 9pt, fill: ink-soft)[#sec.subtitle]
        }
      },
      text(size: 11pt, weight: 600, fill: ink-soft)[#sec.range],
    )
    v(3pt)
    line(length: 100%, stroke: 0.8pt + rule-strong)
    v(5pt)
  })
}

// --- event chip -------------------------------------------------------------

// An assignee's avatar: their initials in a circle of their own colour, matching the
// Positions view on screen.
#let avatar(c, size) = box(
  baseline: size * 0.22,
  width: size,
  height: size,
  radius: 50%,
  fill: swatch(c.color),
  clip: true,
  align(center + horizon, text(size: size * 0.48, fill: white, weight: 700, c.initials)),
)

// One line of a month chip: a fixed-height strip that clips whatever overruns it, so a
// long name shortens the line instead of growing the cell. The height is `1.5em` of the
// caller's text size — enough for the avatars and the descenders.
#let chip-line(body) = block(width: 100%, height: 1.5em, below: 0pt, clip: true, body)

#let chip-body(seg, compact: false) = {
  let size = if compact { 6.4pt } else { 7.2pt }
  set text(size: size)
  set par(leading: 0.4em)
  block(width: 100%, inset: (x: 3pt, y: 2pt), {
    text(weight: 700)[#seg.time]
    if seg.title != "" {
      h(3pt)
      seg.title
    }
    for c in seg.crew {
      linebreak()
      avatar(c, size * 1.25)
      h(2.5pt)
      text(fill: luma(55))[#c.name]
    }
    if seg.note != none {
      linebreak()
      text(weight: 600, fill: luma(70))[#seg.note]
    }
  })
}

// --- time-grid views (day / week) -------------------------------------------
// One column per day, an hour ruler down the left, events placed absolutely.

#let time-grid(sec) = {
  let cfg = sec.grid
  let from = cfg.dayStart
  let to = cfg.dayEnd
  let span = calc.max(to - from, 60)
  let ndays = sec.days.len()

  grid(
    rows: (auto, 1fr),
    columns: (1fr,),
    // day headers, aligned to the same column geometry as the grid below
    block(width: 100%, inset: (bottom: 4pt), grid(
      columns: (gutter,) + (1fr,) * ndays,
      [],
      ..sec.days.map(d => align(center, {
        set text(fill: if d.dim { ink-soft } else { ink })
        text(size: 9.5pt, weight: 700)[#d.head]
        if d.sub != "" {
          h(4pt)
          text(size: 8.5pt, weight: 400, fill: ink-soft)[#d.sub]
        }
      })),
    )),
    block(width: 100%, height: 100%, breakable: false, layout(size => {
      let h-total = size.height
      let col-w = (size.width - gutter) / ndays
      let hour-h = h-total / (span / 60)
      let y-of(min) = (min - from) / 60 * hour-h

      // out-of-scope / weekend day shading (drawn first, under everything)
      for (i, d) in sec.days.enumerate() {
        if d.dim {
          place(dx: gutter + i * col-w, dy: 0pt, rect(width: col-w, height: h-total, fill: dim-fill, stroke: none))
        }
      }

      // hour rules + gutter labels, each label straddling its own line
      for hr in range(calc.ceil(from / 60), calc.floor(to / 60) + 1) {
        let y = y-of(hr * 60)
        place(dx: gutter, dy: y, line(length: size.width - gutter, stroke: 0.4pt + hairline))
        place(dx: 0pt, dy: y - 4.5pt, box(width: gutter - 3pt, align(right, text(
          size: 7pt,
          fill: ink-soft,
        )[#cfg.hourLabels.at(str(hr), default: str(hr))])))
      }

      // column separators + top/bottom frame
      for c in range(0, ndays + 1) {
        place(dx: gutter + c * col-w, dy: 0pt, line(angle: 90deg, length: h-total, stroke: 0.4pt + hairline))
      }
      place(dx: gutter, dy: 0pt, line(length: size.width - gutter, stroke: 0.7pt + rule-strong))
      place(dx: gutter, dy: h-total, line(length: size.width - gutter, stroke: 0.7pt + rule-strong))

      // events
      for seg in sec.segments {
        let col = swatch(seg.color)
        let lane-w = col-w / seg.lanes
        let x = gutter + seg.day * col-w + seg.lane * lane-w
        let y = y-of(seg.start)
        let hh = calc.max(y-of(seg.end) - y, 9pt)
        // White body, colour in the border: a thick accent bar down the left edge and
        // a hairline of the same colour around the rest, so the whole card is framed in
        // the position's colour while the body stays legible in bulk (and photocopies).
        place(dx: x + 0.7pt, dy: y + 0.5pt, block(
          width: lane-w - 1.4pt,
          height: hh - 1pt,
          clip: true,
          radius: 1.6pt,
          fill: white,
          stroke: (left: 1.6pt + col, rest: 0.4pt + col),
          chip-body(seg, compact: lane-w < 34mm),
        ))
      }
    })),
  )
}

// --- month view -------------------------------------------------------------
// Week rows × seven day cells, each listing compact one-line chips.

#let month-grid(sec) = {
  let nrows = calc.ceil(sec.days.len() / 7)
  grid(
    columns: (1fr,) * 7,
    rows: (auto,) + (1fr,) * nrows,
    stroke: 0.4pt + hairline,
    // The day shading rides on the grid cell, not on the content block: a filled
    // block sized to the whole cell paints over the grid's own strokes and the
    // week/day rules disappear.
    ..sec.weekdayHeads.map(w => align(center, block(inset: (y: 3pt), text(
      size: 8.5pt,
      weight: 700,
      fill: ink-soft,
    )[#w]))),
    ..sec.days.map(d => grid.cell(
      fill: if d.dim { dim-fill } else { none },
      block(
        width: 100%,
        height: 100%,
        inset: 3pt,
        clip: true,
        {
          set par(leading: 0.35em)
          text(size: 8pt, weight: 700, fill: if d.dim { luma(150) } else { ink })[#d.num]
          if d.sub != "" {
            h(3pt)
            text(size: 7pt, fill: ink-soft)[#d.sub]
          }
          v(2.5pt, weak: true)
          for chip in d.chips {
            let col = swatch(chip.color)
            block(
              width: 100%,
              inset: (x: 2.5pt, y: 1.2pt),
              radius: 1.4pt,
              below: 1.6pt,
              fill: white,
              stroke: (left: 1.4pt + col),
              // The time range, then one line per assignee — a day/week chip in
              // miniature. Every line is clipped rather than wrapped, so a cell always
              // fits the line budget the caller sized it for: the inner `box` is wider
              // than the cell, which is how you say "don't wrap", and the clipping block
              // then cuts the overflow off at the edge.
              {
                let size = 6.4pt
                set text(size: size)
                let head = box(width: 600%)[
                  #text(weight: 700)[#chip.time]
                  #if chip.label != "" [#h(2.5pt) #chip.label]
                ]
                // The "n open" note rides in its own column rather than at the end of the
                // clipped line: a shift being short-handed is the one thing on the chip a
                // long label must not cut off. The columns sit *inside* the line, so this
                // row is exactly as tall as every other and a cell's height stays the
                // line count the caller budgeted for.
                chip-line(if chip.note == none {
                  head
                } else {
                  grid(
                    columns: (1fr, auto),
                    column-gutter: 3pt,
                    box(width: 100%, clip: true, head),
                    text(weight: 600, fill: luma(70))[#chip.note],
                  )
                })
                for c in chip.crew {
                  chip-line(box(width: 600%)[
                    #avatar(c, size * 1.25)
                    #h(2pt)
                    #text(fill: luma(55))[#c.name]
                  ])
                }
                if chip.crewMore != "" {
                  chip-line(text(fill: ink-soft)[#chip.crewMore])
                }
              },
            )
          }
          if d.more > 0 {
            text(size: 6.4pt, fill: ink-soft)[#d.moreLabel]
          }
        },
      ),
    )),
  )
}

// --- footer band ------------------------------------------------------------
// Legend + "in this view" figures, and — when the printed hours left something out —
// a note naming what is missing, so the page never lies by omission.

#let footer-band(sec) = {
  let has-band = sec.legend.len() > 0 or sec.stats.len() > 0
  if not has-band and sec.dropped.count == 0 { return }
  // The rule closes off the calendar, so it starts where the calendar does: on a
  // day/week page that is past the hour-label gutter, never underneath the labels.
  // A month grid has no gutter and runs the full width.
  let lead = if meta.view == "month" { 0pt } else { gutter }
  block(width: 100%, above: 5pt, {
    pad(left: lead, line(length: 100%, stroke: 0.4pt + hairline))
    v(4pt)
    if has-band {
      grid(
        columns: (1fr, auto),
        align: (left + horizon, right + horizon),
        column-gutter: 10pt,
        {
          set text(size: 7.5pt)
          sec
            .legend
            .map(l => box(baseline: 1.5pt, {
              box(width: 6pt, height: 6pt, radius: 1pt, fill: swatch(l.color))
              h(3pt)
              l.label
            }))
            .join(h(9pt))
        },
        {
          set text(size: 7.5pt, fill: ink-soft)
          sec.stats.map(s => [#s.k #text(weight: 700, fill: ink)[#s.v]]).join(h(9pt))
        },
      )
    }
    if sec.dropped.count > 0 {
      v(3pt)
      set text(size: 7pt, fill: luma(85))
      text(weight: 700)[#sec.dropped.label]
      h(4pt)
      // Semicolons between entries — the entries themselves are dot-separated.
      sec.dropped.items.join([; ])
    }
  })
}

// --- render -----------------------------------------------------------------

#let section-body(sec) = {
  if sec.days.len() == 0 {
    align(center + horizon, text(size: 10pt, fill: ink-soft)[#labels.empty])
  } else if meta.view == "month" {
    month-grid(sec)
  } else {
    time-grid(sec)
  }
}

#for (i, sec) in doc.sections.enumerate() {
  if i > 0 { pagebreak() }
  block(width: 100%, height: 100%, grid(
    rows: (auto, 1fr, auto),
    columns: (1fr,),
    section-header(sec),
    block(width: 100%, height: 100%, section-body(sec)),
    footer-band(sec),
  ))
}
