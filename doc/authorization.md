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
     :params {:message_type "http://puppetlabs.com/rpc_blocking_request"
              :targets "pcp://client02.example.com/agent"}}

And then matched by trapperkeeper-authorization.  For notes on how to
configure tk-auth see
https://github.com/puppetlabs/trapperkeeper-authorization/blob/master/doc/authorization-config.md
