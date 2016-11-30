## pcp-broker

A message broker for the PCP protocol

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
