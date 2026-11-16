# UX: Nuzul

Owner: UX Designer · Status: DRAFT

## Guest journeys
1. **Search → book (the core path):** Home (city, dates, guests) → Results (list, filters on the left, sort by price) →
   Hotel (photos, room types with a "Reserve" button) → **Hold screen** (10:00 countdown, summary, fake card form) →
   Confirmation (reservation number, email sent).
2. **Manage:** My reservations → detail → Cancel (shows whether it is free or non-refundable *before* the click).
3. **Sold out mid-flow:** a 409 on Reserve shows "This room was just taken" and the other room types for the same dates, never a generic error.
4. **Hold expired:** the countdown reaches 0 → "Your hold expired, check availability again" with one button.

## Hotel-admin journeys
1. My hotels → hotel → Room types.
2. **Calendar grid** per room type: 90 days × (total, reserved, price). Bulk edit a date range (set total / price).
   Refuses `total < reserved` inline, with the count shown.
3. Photos: drag and drop, with upload progress (direct to S3).

## Principles
- Dates are always shown as nights ("3 nights, 27–30 Dec").
- The price shown is the total for the stay, with the per-night breakdown expandable. No surprise at the hold step.
- Every async state has a skeleton or loading state; every error says what to do next.
- Mobile-first for guests; desktop-first for the admin grid.
