# UI: Nuzul

Owner: UI Designer · Status: DRAFT

## Direction
"Riad calm": warm off-white surfaces, deep terracotta primary, zellige-green accent, generous whitespace.
Photography carries the page, and the chrome stays quiet.

## Tokens (CSS custom properties, mapped onto a PrimeNG preset)
| Token | Light | Dark |
|---|---|---|
| `--bg` | #FBF8F3 | #16130F |
| `--surface` | #FFFFFF | #201C17 |
| `--text` | #2A2420 | #EDE6DC |
| `--muted` | #6E6259 | #A89C90 |
| `--primary` | #B5532E (terracotta) | #E07A50 |
| `--accent` | #1F6F5C (zellige) | #4FB59A |
| `--danger` | #B42318 | #F97066 |
Type: "Inter" for UI, "Fraunces" for hotel names and headings. 4 px spacing scale; 12 px radius on cards.

## Key components
SearchBar (city autocomplete, date-range picker, guests stepper) · HotelCard · RoomTypeRow · HoldCountdown ·
PriceBreakdown · AvailabilityGrid (admin, virtual scroll) · ProblemToast (maps `problem+json` codes to i18n messages).

## Accessibility
WCAG 2.2 AA contrast for all text tokens; full keyboard support for the date picker and grid; `aria-live` on the countdown at each 1-minute step.
