# Roles & permissions (identity-service)

The hotel app's Manager → Roles and Owner → Roles screens. Hotels don't create roles: they give the module roles
(MANAGER, FRONT_OFFICE, HOUSEKEEPING, LAUNDRY, MAINTENANCE, KITCHEN, BAR, INVENTORY, FINANCE) to people at a hotel
at one of three levels: FULL, EDIT ("Can edit") and VIEW ("View only"). What each level allows is enforced by the
services' guards (`@access.has`, `@access.atLeast(role, level)`, `@access.manager()`). The web app describes them in
`packages/api/src/roles.ts` (`ROLE_PERMISSIONS`, `MANAGER_ONLY_ACTIONS`).

## Endpoints

| Method | Path | Who | What |
|---|---|---|---|
| GET | `/identity/users?hotelId=` | manager, owner | people with a role at the hotel (or business-wide), each with every grant: `id, role, level, hotelId, paused, expiresAt, until, grantedBy` |
| POST | `/identity/users/{id}/roles` | manager, owner | `{role, level?, hotelId?, until?}` gives a role. `until` (yyyy-mm-dd) is the last day; the role ends at midnight after it, Lagos time. `expiresAt` (instant) is still accepted |
| PUT | `/identity/users/{id}/roles/{grantId}` | manager, owner | `{change, level?, reason?, until?, allowLastRole?}` with change `CHANGE_LEVEL`, `PAUSE`, `RESUME`, `SET_UNTIL` (`until` null makes the role permanent) or `REMOVE` |
| POST | `/internal/users/grants/expire` | internal key | runs the expiry job now (QA and operations) |

## Rules (UserService)

- A manager or the owner (or H.M.S). A read-only token (every role VIEW) never passes `@access.manager()`.
- No one changes their own roles: "You can't change your own access. Ask the owner or another manager".
- The owner role is not given here ("The owner role is given by H.M.S or by transferring ownership"), and only H.M.S changes an owner's grant. H.M.S roles are not given by hotels.
- A manager gives and changes roles only at their own hotels ("That role is at a hotel you don't manage"); the owner at any hotel of the business. A manager may give MANAGER, matching invites (`POST /users`).
- A role already held at the hotel is not given again: "... already has Kitchen here (Full). Change the level instead" (409). Same level, already paused, not paused and no end date are refused with a sentence too.
- A person's only active role is not removed: "Kitchen is X's only active role. Give them another role first, or pause this one instead". The remove-person flows (Staff → Remove from hotel, Owner → Users → Remove access, Admin team) send `allowLastRole: true`.
- A last day in the past: "The last day must be today or later". An unknown role or level: `"BOSS" is not a valid role. Use one of: ...`.

## Temporary roles

`expires_at` holds the midnight after the last day (Africa/Lagos). Tokens only ever carry active grants, so the role
stops working at that midnight. `RoleGrantExpiryJob` runs every hour at :05 (Lagos) and deletes ended grants, logs
an `ACCESS_CHANGED` EXPIRED event and tells the person.

## Activity log and notices

Every change publishes `ROLE_GRANTED` or `ACCESS_CHANGED` (change, role, detail, actor), which platform-service's
activity log stores. The person gets an in-app notice (`NOTICE`, kind `ACCESS`, link `/profile`), for example "You now
have Kitchen · View only" or "Your Kitchen access is paused".

## Token freshness

Access tokens (1 hour) carry roles and every service checks them locally. We chose **refusing stale tokens** over
waiting for expiry:

- `common` `AccessRevocations` in every service follows `role-granted` and `access-changed`. It reads directly with
  no consumer group, rewinding one token lifetime on start, and remembers when each person's access last changed.
- `JwtFilter` does not authenticate a token issued in an earlier second than that change. Protected paths answer
  401 `Your access has changed. Sign in again to continue` with the header `X-Access-Changed: true`.
- The web app swaps the token. The dashboard shell compares `/users/me` role levels with the token, or sees that
  401, and goes through `/api/session/refresh?from=<page>`. That route calls `POST /identity/auth/refresh` with the
  refresh cookie, keeps the active role if it is still held, and comes back to the page. A server action that gets
  the 401 does the same. If the refresh fails (the account is paused), the person goes to sign-in.
- Limits: the change reaches a service as fast as Kafka delivers it (normally under a second). A token issued in the
  same second as the change is accepted. If Kafka is down, tokens fall back to their 1-hour expiry. Support-access
  tokens (`act`) are not checked.
