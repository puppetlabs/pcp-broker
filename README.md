## pcp-broker

A message broker for the PCP protocol

## Installing

To use this service in your trapperkeeper application, simply add this
project as a dependency in your leiningen project file:

[![Clojars Project](http://clojars.org/puppetlabs/pcp-broker/latest-version.svg)](http://clojars.org/puppetlabs/pcp-broker)

And then see [these notes on configuring](doc/configuration.md)

## Running the server

For development purposes you can run a broker out of a checkout using
the *insecure* certificates provided in test-resources/ with the
following command:

    lein tk

## Documentation

Look [here](doc/).

## Maintenance

Maintainers: Alessandro Parisi <alessandro@puppet.com>, Michael Smith
<michael.smith@puppet.com>, Michal Ruzicka <michal.ruzicka@puppet.com>.

Contributing: Please refer to [this][contributing] document.

Tickets: File bug tickets at https://tickets.puppet.com/browse/PCP and add the
`pcp-broker` component to the ticket.

[contributing]: CONTRIBUTING.md
