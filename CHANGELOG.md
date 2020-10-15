# 1.5.7
This is a maintenance release
* (PCP-900) Close connections after CRL update. Connections closes are throttled to prevent a thundering herd of reconnection requests.
* (PE-30041) Remove harmful term from pcp-broker config (controller-whitelist -> controller-allowlist)
* Lower default idle-timeout from 15 minutes to 6
* Allow configuration websocket idle-timeout

# 1.5.6

This is a maintenance release
* Update deploy repositories

## 1.5.5

This is a maintenance release
* Update clj-parent (4.4.1)
* Update pcp-client (1.3.1)

## 1.5.3

This is a maintenance release
* Removes the `close!` call before disconnecting. Having both `close` and `disconnect` created a race condition where `close` could close the connection too quickly. Manual testing with a check to ensure the session was still open nullified any resource saving benefits of having the disconnect.

## 1.5.2

This is a maintenance release
* [PCP-862](https://tickets.puppetlabs.com/browse/PCP-862) Adds `disconnect` call when ending a superseded session after `close!` in order to free resources more quickly

## 1.5.0

This is a feature release

* Add support for Java 9

## 1.4.4

This is a maintenance release

* Includes updated Japanese translations for changes in 1.4.3.

## 1.4.3

This is a maintenance release

* [PCP-777](https://tickets.puppetlabs.com/browse/PCP-777) Reject connections
  before websocket upgrade rather than after they've connected.

## 1.4.2

This is a maintenance release

* [PCP-491](https://tickets.puppetlabs.com/browse/PCP-491) Make max message
  size for receiving messages configurable.
* Includes updated Japanese translations.

## 1.4.1

This is a maintenance release

* [PCP-760](https://tickets.puppetlabs.com/browse/PCP-760) Fix an issue where
  requests received immediately after connecting to a controller may be ignored.

## 1.4.0

This is a feature release.

* [PCP-732](https://tickets.puppetlabs.com/browse/PCP-732) Refine logging.
* [PCP-737](https://tickets.puppetlabs.com/browse/PCP-737) Handle events on
  stale connections gracefully.
* Bump clj-parent to 1.2.1, which updates trapperkeeper-metrics to 1.1.0

## 1.3.4

This is a maintenance release

* Bump clj-parent to 1.1.0 which adds versioning for metrics-clojure, bump
  metrics-clojure from 2.5.1 to 2.6.1 as a result, remove unused dependencies

## 1.3.3

This is a maintenance release

* Bump clj-parent to 1.0.0 which upgrades jetty to 9.4

## 1.3.2

This is a maintenance release

* Bump clj-parent to 0.8.1 which upgrades structured-logging

## 1.3.1

This is a maintenance release

* Bump the default controller-disconnection-graceperiod setting from 45000 to
  90000, to allow ample time for service restarts.
* Bump clj-pcp-client to version 1.1.5

## 1.3.0

This is a feature release.

The major update is a move to trapperkeeper-metrics 1.0.0. Also includes bumps
to clj-parent 0.6.1 - which brings i18n 0.8.0 - and clj-pcp-client 1.1.4.

## 1.2.1

This is a maintenance release

* [PCP-731](https://tickets.puppetlabs.com/browse/PCP-731) Bump clj-parent to
  0.4.3 to pickup i18n 0.7.1 for a change in pot file name.

## 1.2.0

This is a feature release.

The major updates are:
* [PCP-701](https://tickets.puppetlabs.com/browse/PCP-701) Reject client
  connections if outgoing connections are configured but not connected. This
  will also cause status to report an "error" state if the broker is running
  and outgoing connections are not connected.
* [PCP-727](https://tickets.puppetlabs.com/browse/PCP-727) Add connection
  limit to pcp-broker so total connections can be capped.

## 1.1.1

This is a maintenance release

* Remove authorization.conf file to avoid conflicts when packaged alongside
  other services

## 1.1.0

This is a feature release.

The major updates are:
* [PCP-600](https://tickets.puppetlabs.com/browse/PCP-600) Add support for
  inventory updates, which were introduced in the [PCP v2
  specifications](https://github.com/puppetlabs/pcp-specifications/blob/master/pcp/versions/2.0/inventory.md#inventory-messages)
* [PCP-681](https://tickets.puppetlabs.com/browse/PCP-681) Add the
  `controller-uris`  and `controller-whitelist` configuration options. These
  are, respectively, a list of controllers to which the broker may connect, and
  a list of message types the broker may send to controllers.

## 1.0.1

This is a maintenance release

* Logs failure to deliver error messages and associate response to debug. These
are normal behavior if the client disconnects after sending a message.

## 1.0.0

This is a major breaking release with significant new features. It introduces
PCP v2 while maintaining limited compatibility with PCP v1. See
[pcp-specifications](https://github.com/puppetlabs/pcp-specifications)
for details of changes in PCP v2. Note that in this release, pcp-broker ignores
the `subscribe` property of inventory requests and does not send inventory
updates.

The major changes in behavior are

* Delayed message delivery is no longer supported; message expiration is ignored
  in v1 messages.
* Multicast messaging is no longer supported; only messages with a single target
  will be allowed.
* Session association is deprecated. Client types may be specified by appending
  them to the connection URI; when not supplied client type defaults to agent.
  The client type established on connection must be used in any
  session_association requests.
* Debug chunks are no longer created or retransmitted.

Specific changes included

* [PCP-645](https://tickets.puppetlabs.com/browse/PCP-645) Implement PCP v2
* [PCP-637](https://tickets.puppetlabs.com/browse/PCP-637) Remove message
  persistence, multicast, and ActiveMQ
* [PCP-597](https://tickets.puppetlabs.com/browse/PCP-597) Switch to using
  clj-parent for dependency versioning and get the broker name from webserver
  context.

## 0.8.5

This is a maintenance release to bump clj-pcp-common to 0.5.5.

## 0.8.4

This is a maintenance release to publish 0.8.2 to clojars.

## 0.8.3

This is a maintenance release to publish to an internal clojars repository.

## 0.8.2

This is a private security release, never published to clojars.

* [PCP-655](https://tickets.puppetlabs.com/browse/PCP-655) PCP broker builds
  ring requests without enough validation

## 0.8.1

This is a maintenance release

* [PCP-523](https://tickets.puppetlabs.com/browse/PCP-523) Add new unit and
  integration tests for message processing functions
* Update to (clj-i18n/0.4.3) and (pcp-common/0.5.4) for a performance-related
  bug fix

## 0.8.0

This is an improvement and bugfix release

* [PCP-525](https://tickets.puppetlabs.com/browse/PCP-525) Rely on
  pcp-common.message for validating Messages
* [PCP-385](https://tickets.puppetlabs.com/browse/PCP-385) Add pcp-access logger
  and improve docs
* [PCP-354](https://tickets.puppetlabs.com/browse/PCP-354) Add profile to use
  Puppet internal mirrors for deps
* [PCP-538](https://tickets.puppetlabs.com/browse/PCP-538) Restore initial
  delivery of expired messages
* [PCP-531](https://tickets.puppetlabs.com/browse/PCP-531) Allow cert chains in
  broker certificate
* [PCP-489](https://tickets.puppetlabs.com/browse/PCP-489) Improve onMessage
  handling
* [PCP-526](https://tickets.puppetlabs.com/browse/PCP-526) Don't do schema
  validation in tests by default
* [PCP-528](https://tickets.puppetlabs.com/browse/PCP-528) Fix unit and
  integration lein profiles
* [PCP-524](https://tickets.puppetlabs.com/browse/PCP-524) Add error_message
  data's id when available
* [PCP-516](https://tickets.puppetlabs.com/browse/PCP-516) CapsuleLog's sender
  may not be a PCP URI

## 0.7.3

This is an accidental release with the contents of 0.8.0

## 0.7.2

This is a bugfix release

* [PCP-506](https://tickets.puppetlabs.com/browse/PCP-506) Make vNext route
  actually optional.

## 0.7.1

This is a bugfix release

* [PCP-162](https://tickets.puppetlabs.com/browse/PCP-162) Externalize strings
  for localization.
* [PCP-245](https://tickets.puppetlabs.com/browse/PCP-245) Avoid delivering
  expired messages.
* [PCP-370](https://tickets.puppetlabs.com/browse/PCP-370) Removed old test
  SSL certificates.
* [PCP-384](https://tickets.puppetlabs.com/browse/PCP-384) Check authorization
  of `associate_request` and `inventory_request` messages.
* [PCP-448](https://tickets.puppetlabs.com/browse/PCP-448) Avoid heavy thread
  contention when disconnecting many clients simultaneously; previously
  included in pcp-broker 0.6.2.
* [PCP-467](https://tickets.puppetlabs.com/browse/PCP-467) Disable Prismatic
  schema checks on internal functions for production.
* [PCP-485](https://tickets.puppetlabs.com/browse/PCP-485) Speed up broker
  `inventory_response` generation.
* [PCP-487](https://tickets.puppetlabs.com/browse/PCP-487) Increase PCP
  message expiry in tests to allow for more schema checks during testing.
* [PCP-496](https://tickets.puppetlabs.com/browse/PCP-496) Set
  `inventory_response` expiration as the last step before sending messages,
  to avoid message expiring while creating it.

## 0.7.0

This is an improvement and bugfix release

* [PCP-146](https://tickets.puppetlabs.com/browse/PCP-146) Added cloverage
  targets to enable us to see where test coverage is lacking.
* [PCP-147](https://tickets.puppetlabs.com/browse/PCP-147) Extended unit test
  coverage.
* [PCP-193](https://tickets.puppetlabs.com/browse/PCP-193) Removed old
  status webroute in preference to using trapperkeeper-status.  To migrate:
 * Add
   `puppetlabs.trapperkeeper.services.status.status-service/status-service`
   to your `bootstrap.cfg`.
 * Mount
   `puppetlabs.trapperkeeper.services.status.status-service/status-service`
   via your web-routing configuration.
* [PCP-199](https://tickets.puppetlabs.com/browse/PCP-199) Renamed
  `:websocket` webroute to `:v1`.
* [PCP-234](https://tickets.puppetlabs.com/browse/PCP-234) Added
  additional test certificates.
* [PCP-222](https://tickets.puppetlabs.com/browse/PCP-222) Fixed sort
  order of default package-supplied authorisation rule.
* [PCP-194](https://tickets.puppetlabs.com/browse/PCP-194)
  Reimplemented internal Capsule and Connection types to use a
  defrecord rather than just a map schema.
* [PCP-195](https://tickets.puppetlabs.com/browse/PCP-195) Added
  `in-reply-to` as an envelope property consistently.
* [PCP-250](https://tickets.puppetlabs.com/browse/PCP-250) Added
  `:vNext` webroute where developing protocol changes (such as
  PCP-195) can be staged.
* [PCP-295](https://tickets.puppetlabs.com/browse/PCP-295) Fixed
  status callback registered with trapperkeeper-status.
* [PCP-301](https://tickets.puppetlabs.com/browse/PCP-301) Updated
  trapperkeeper dependency to version that supports HUP behaviour.
* [PCP-292](https://tickets.puppetlabs.com/browse/PCP-292) Changed
  `:vNext` webroute so it is only mounted when named in a web-routing
  configuration.
* [PCP-294](https://tickets.puppetlabs.com/browse/PCP-294) Close
  connections when the broker is not in a running state.

Note that the bugfix from 0.6.2 was not included in 0.7.0.

## 0.6.2

This is a bugfix release

* [PCP-448](https://tickets.puppetlabs.com/browse/PCP-448) Avoid heavy thread
  contention when disconnecting many clients simultaneously.

## 0.6.1

This is a bugfix release

* [#91](https://github.com/puppetlabs/pcp-broker/pull/91) Correct a logging
  invocation in the message expired codepath.

## 0.6.0

This is an improvement and bugfix release

* [PCP-115](https://tickets.puppetlabs.com/browse/PCP-115) Convert logging to
  use puppetlabs/structured-logging and revisit log levels and messages.
* [PCP-124](https://tickets.puppetlabs.com/browse/PCP-124) Close
  unauthenticated websockets sessions with close-code 4003
* [PCP-132](https://tickets.puppetlabs.com/browse/PCP-132) Change the behaviour
  of `inventory_request` to limit to currently connected matching identities.
* [PCP-126](https://tickets.puppetlabs.com/browse/PCP-126) Limit the message
  redelivery timeout to be between 1..15 seconds.

## 0.5.0

This is the first public release to clojars.

* [PCP-46](https://tickets.puppetlabs.com/browse/PCP-46) Release to clojars
  rather than internal nexus servers.

## 0.4.1

This is a bugfix release

* [#80](https://github.com/puppetlabs/pcp-broker/pull/80) Fix the fake ring
  request maker allow query-params to be matched.

## 0.4.0

This is a feature release

* [PCP-88](https://tickets.puppetlabs.com/browse/PCP-88) Authorization system
  switched to trapperkeeper-authorization.  See [authorization](doc/authorization.md)
  for notes on how to configure this.

## 0.3.0

This is a feature and maintenance release

* [CTH-134](https://tickets.puppetlabs.com/browse/CTH-134) Server
  identity is derived from the webserver certificate.
* [PCP-37](https://tickets.puppetlabs.com/browse/PCP-37) Added
  configurations to drive ezbake projects.
* Added CONTRIBUTING.md and issue tracker breadcrumbs to README.md to
  prepare for a public clojars release.

## 0.2.2

This is a bugfix release

* [CTH-351](https://tickets.puppetlabs.com/browse/CTH-351) Fix broker startup
  to always start a working broker.
* Adopt cljfmt style guide from https://github.com/puppetlabs/pl-clojure-style
* Relicence to APL 2.0 (from internal commercial)
