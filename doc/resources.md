# Resources

Resource usage of pcp-broker primarily means memory required by the JVM and
file descriptors used. Usage scales linearly with number of active connections.

Specifically, the pcp-broker requires ~40KB of memory (when restricted with the
JVM option `-Xmx`) and 1 file descriptor per connection, with a baseline of
~60MB and 200 file descriptors. For a deployment of 100 agents, expect to
configure the JVM with at least `-Xmx64m` and 300 file descriptors.

Message handling requires minimal additional memory.
