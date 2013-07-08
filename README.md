ProtobufRpcEngine
=================

RPC module completely extracted from Hadoop-hdfs, and converted to be standalone functional. 


Future works:
.Support some of the Retry strategies
.Add support specificly for huge objects demanding network transportation.

.Add Server echo
.Support customed events(user is able to throw his own exception and catch it), through this is not a recommended strategy!
.replace Handler array with a threadpool
.light weight task manager
