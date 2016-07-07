# Message authorization

Messages can be allowed or denied using the trapperkeeper-authorization subsystem.

https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md

## Message mapping

PCP Message are mapped into ring requests, on the `/pcp-broker/send` path.

As a worked example, the envelope of a message used by the ping
application would look something like this:


    {:id "3790c4a2-dd71-41bf-bd6d-573779b38657"
     :sender "pcp://client01.example.com/ruby-pcp-client-2251"
     :targets [ "pcp://client02.example.com/agent" ]
     :message_type "http://puppetlabs.com/rpc_blocking_request"}

This would be transformed into the following ring request:

    {:request-method :post
     :remote-addr "192.168.1.22:36362"
     :uri "/pcp-broker/send"
     :ssl-client-cert (X509-certificate-for "client01.example.com")
     :form-params {}
     :query-params {"message_type" "http://puppetlabs.com/rpc_blocking_request"
                    "sender" "pcp://client01.example.com/ruby-pcp-client-2251"
                    "targets" "pcp://client02.example.com/agent"}
     :params {"message_type" "http://puppetlabs.com/rpc_blocking_request"
              "sender" "pcp://client01.example.com/ruby-pcp-client-2251"
              "targets" "pcp://client02.example.com/agent"}}

And then this can be matched by trapperkeeper-authorization with the following `authorization.conf`.

``` HOCON
# authorization.conf
authorization: {
  version: 1
  rules: [
    {
      name: "pxp command message"
      match-request: {
         type: path
         path: "/pcp-broker/send"
         query-params: {
           message_type: [
             "http://puppetlabs.com/rpc_blocking_request"
           ]
         }
      }
      allow: [
        client01.example.com
      ]
      sort-order: 400
    },
    {
      name: "pcp message"
      match-request: {
         type: path
         path: "/pcp-broker/send"
      }
      allow-unauthenticated: true
      sort-order: 420
    },
  ]
}
```

Session association and inventory requests are handled separately from other PCP Messages, and have
their own authorization rules. PCP Messages with type `http://puppetlabs.com/associate_request` are
mapped into ring requests on the `pcp-broker/connect` path. Messages with type
`http://puppetlabs.com/inventory_request` do not currently have separate authorization, and are
allowed for any associated client.

Session association requests can be matched by trapperkeeper-authorization with the following `authorization.conf`.

``` HOCON
# authorization.conf
authorization: {
  version: 1
  rules: [
    {
      name: "pcp association"
      match-request: {
         type: path
         path: "/pcp-broker/connect"
      }
      allow-unauthenticated: true
      sort-order: 400
    },
  ]
}
```

For further notes on how to configure trapperkeeper-authorization see
https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md
