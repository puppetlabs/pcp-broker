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
