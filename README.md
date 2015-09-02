# pcp-broker

A message broker for the PCP protocol

## Installing

We currently publish to the internal nexus server.  To get access to
these you will need to add the following to your project.clj.

```
:dependencies [[puppetlabs/pcp-broker "0.2.0-SNAPSHOT"]]

:repositories [["releases" "http://nexus.delivery.puppetlabs.net/content/repositories/releases/"]
               ["snapshots" "http://nexus.delivery.puppetlabs.net/content/repositories/snapshots/"]]
```

## Running the server

For development purposes you can run a broker out of a checkout using
the *insecure* certificates provided in test-resources/ with the
following command:

    lein tk

## Documentation

Look [here](docs/).
