# Server-side pagination

Every list that grows without limit is paged by the service that owns it. The browser never loads a whole
history to show ten rows of it. This page is the contract, the list of converted endpoints and the progress
checklist.

## The contract

### Request

| Parameter | Meaning |
|---|---|
| `page` | 0-based page. Absent means 0. Negative is a 400 ("page counts from 0"). |
| `size` | Rows per page, 1 to 100. Absent means 25. Anything else is a 400 ("size is 1 to 100"). |
| `sort` | `field` or `field,asc` / `field,desc` over the fields the list allows (unknown field is a 400). Absent means the list's default, newest first. The default order always follows as the tie-breaker, so pages never overlap. |
| filters | The list's own: usually `q` (case-insensitive contains search), `status`, `from` / `to` (Lagos days, both inclusive), and others named per endpoint below. |

Filtering, counting and sorting happen in the database (a JPA `Specification` or SQL), never in memory.
`common/.../paging/Paging.java` holds the checks (`Paging.of(page, size, sort)`, `Paging.sort(...)`,
`Paging.windowFrom(from)`, `Paging.like(q)`).

### Response

`ApiResponse<PagedResponse<T>>`: `content`, `number`, `size`, `totalElements`, `totalPages`, `first`, `last`,
`empty`.

A list whose screen shows tab counts or money over the whole filtered range answers
`ApiResponse<PageWithCounts<T>>`: the same fields plus

- `counts`: tab counts over the whole filtered range, computed with every filter except the one the tabs
  choose (usually `status`), so switching tabs never changes the numbers. `counts.all` is the sum.
- `totals`: money over the whole filtered range (naira, two decimals).

Counts and totals are the same on every page.

### Natural windows

Lists that are read as "recent" (payments, expenses, notifications, approvals) default `from` to 90 days
before today (Lagos) when it isn't given. Asking for an earlier `from` reaches older rows.

### Backwards compatibility

Where an endpoint used to answer a plain list and something other than a screen still reads it (another
service, an operations script), the endpoint keeps that answer when **neither** `page` nor `size` is sent,
and answers a page as soon as either is. Screens always send `page` and `size`. No caps (`Top300` and the
like) remain on either form: the unpaged form is for internal callers only and returns every match.

### Exports

An export covers the whole filtered range, not the page on screen: the web reads every page (100 at a time)
with the same filters and writes one CSV, so its row count equals `totalElements`.

## The web side

- `packages/api/src/data/paging.ts`: `Paged<T>`, `PageResult<T>`, `toPaged`, `pageQuery`, `allPages`,
  `listQueryFrom` (the URL's 1-based `?page=` and filters → the service's query).
- `packages/ui/src/shared/list-params.ts`: `useListParams` (page, `q`, `status`, `from`, `to`, `sort` and
  extra filters in the URL; debounced search; any filter change goes back to page 1; Back restores).
- `packages/ui/src/shared/data-table.tsx`: `DataTable server={{ page, onPage, sort, onSort, sortKeys }}`,
  `filtered` + `onClearFilters` (no-match state), `error` + `onRetry` (load error state).
- `packages/ui/src/shared/server-pager.tsx`: "Showing 26–48 of 48" and the page numbers.

## Converted endpoints

(Filled in as each list is converted.)

## Progress

- [x] Contract, `Paging`, `PageWithCounts`, web kit
