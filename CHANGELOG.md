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

This is a feature and maintance release

* [CTH-134](https://tickets.puppetlabs.com/browse/CTH-134) Server
  identity is derived from the webserver certificate.
* [PCP-37](https://tickets.puppetlabs.com/browse/PCP-37) Added
  configurations to drive ezbake projects.
* Added CONTRIBUTING.md and issue tracker breadcrumbs to README.md to
  prepare for a public clojars release.

## 0.2.2

This is a bugfix release.

* [CTH-351](https://tickets.puppetlabs.com/browse/CTH-351) Fix broker startup
  to always start a working broker.
* Adopt cljfmt style guide from https://github.com/puppetlabs/pl-clojure-style
* Relicence to APL 2.0 (from internal commercial)
