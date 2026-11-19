# Roles & permissions (identity-service)

The hotel app's Manager → Roles and Owner → Roles screens. Hotels don't create roles: they give the module roles
(MANAGER, FRONT_OFFICE, HOUSEKEEPING, LAUNDRY, MAINTENANCE, KITCHEN, BAR, WAITER, CASHIER, INVENTORY, FINANCE) to people at a hotel
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

- A manager or the owner (or Hospio360). A read-only token (every role VIEW) never passes `@access.manager()`.
- No one changes their own roles: "You can't change your own access. Ask the owner or another manager".
- The owner role is not given here ("The owner role is given by Hospio360 or by transferring ownership"), and only Hospio360 changes an owner's grant. Hospio360 roles are not given by hotels.
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

## Waiter and cashier (venue type, phase 3)

`WAITER` and `CASHIER` are outlet floor roles for restaurants, bars and lounges, offered at hotels too; both start at Can edit.
A waiter takes orders and serves, on the orders they opened (Full access = head waiter: anyone's); a cashier takes payment for any
open tab or sale and keeps the CASHIER till. Neither marks a dish ready, cancels what was sent or changes the menu. Whether waiters
take payment (and cash) is Hotel setup → Restaurant & bar (`OUTLETS.waitersTakePayment`, `waiterCash`). The full table is in
`docs/plan-venue-type.md` (Phase 3) and `packages/api/src/roles.ts` (`ROLE_PERMISSIONS.WAITER`, `.CASHIER`).

## Discounts, voids and outlets (venue type, phase 4)

- Who gives a discount on a restaurant or bar sale without asking is a location setting, not a level: `OUTLETS.discountRoles`
  (cashier and bar by default) up to `APPROVAL_LIMITS.discountPct`; managers and owners any. Everyone else, a bigger discount and
  every complimentary item is a request a manager or the owner decides.
- Voiding something already sent, or a whole sale that was sent, stays with Full access / a manager; anyone else working the bill
  asks for it from their screen.
- A manager decides a request from their dashboard, or on the requester's screen with their own PIN (the owner: password);
  identity counts wrong tries and locks as it does for PIN sign-in. Nobody approves their own request.
- A waiter, cashier, bar or kitchen person can be kept to some outlets of the location (fnb `staff_outlets`); the role and level
  are unchanged.

## A location without rooms (venue type, phase 5)

At a lounge, bar or restaurant (`hasAccommodation` false) Roles & Permissions offers no Front office or Housekeeping, and every
role's list leaves out the lines about rooms, stays, folios, linen and room service (`packages/api/src/roles.ts`
`rolePermissions(role, stays)`, `managerOnlyActions(stays)`; the services' guards are the same, the lines just have nothing to act
on there). The owner's role reads "Owner" in a business that is not hotels only ("Hotel owner" otherwise), a manager "Run this
lounge: sales, tabs, staff, money" (`roleLabel`, `roleDescription`). Server messages about roles and shared devices name the
location ("That role is at a location you don't manage", "Only the lounge's managers and the owner manage its shared devices");
a hotels-only business reads "hotel" as before.
