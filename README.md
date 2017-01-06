## pcp-broker

A message broker for the PCP protocol. It now supports PCP v2, with
limited support for PCP v1 clients:

* Delayed message delivery is no longer supported; message expiration is ignored in v1 messages.
* Multicast messaging is no longer supported; only messages with a single target will be allowed.
* Session association is deprecated. Client types may be specified by appending them to the
  connection URI; when not supplied client type defaults to agent. The client type established
  on connection must be used in any session_association requests.
* Debug chunks are no longer created or retransmitted.

## Installing

To use this service in your trapperkeeper application, simply add this
project as a dependency in your leiningen project file:

[![Clojars Project](http://clojars.org/puppetlabs/pcp-broker/latest-version.svg)](http://clojars.org/puppetlabs/pcp-broker)

And then see [these notes on configuring](doc/configuration.md)

## Running the server

For development purposes you can run a broker out of a checkout using
the *insecure* certificates provided in test-resources/ with either
the following command:

    lein tk

(This one runs the broker with schema validations _disabled_, i.e. the
same as in production.)

Or with:

    lein tkv

(This one runs the broker with schema validations _enabled_ for greater
scrutiny.)

## Documentation

Look [here](doc/).

## Contributing

Please refer to [this][contributing] document.

[contributing]: CONTRIBUTING.md
