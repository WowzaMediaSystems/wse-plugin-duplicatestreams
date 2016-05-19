# DuplicateStreams
The **ModuleDuplicateStreams** module for [Wowza Streaming Engine™ media server software](https://www.wowza.com/products/streaming-engine) enables you to duplicate a stream from one application instance to another instance on the same Wowza media server.

## Prerequisites
Wowza Streaming Engine 4.0.0 or later is required.

## Usage
This module can only be used to duplicate streams from an application instance to another application instance on the same Wowza media server (it can't be used to duplicate a stream from the application instance to an application instance on a different Wowza media server).

You should use this module, instead of the Push Publishing feature, because it will incur less overhead to perform the same functionality.

## More resources
[Wowza Streaming Engine Server-Side API Reference](https://www.wowza.com/resources/WowzaStreamingEngine_ServerSideAPI.pdf)

[How to extend Wowza Streaming Engine using the Wowza IDE](https://www.wowza.com/forums/content.php?759-How-to-extend-Wowza-Streaming-Engine-using-the-Wowza-IDE)

Wowza Media Systems™ provides developers with a platform to create streaming applications and solutions. See [Wowza Developer Tools](https://www.wowza.com/resources/developers) to learn more about our APIs and SDK.

To use the compiled version of this module, see [How to duplicate streams to another application instance (DuplicateStreams)](https://www.wowza.com/forums/content.php?613-How-to-duplicate-streams-to-another-application-instance-%28ModuleDuplicateStreams%29).

## Contact
[Wowza Media Systems, LLC](https://www.wowza.com/contact)

## License
This code is distributed under the [Wowza Public License](https://github.com/WowzaMediaSystems/wse-plugin-duplicatestreams/blob/master/LICENSE.txt).
