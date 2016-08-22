# aufi [![Build Status](https://travis-ci.org/stylefruits/aufi.svg?branch=master)](https://travis-ci.org/stylefruits/aufi)

__aufi__ is a service for image upload, retrieval and resizing.

## Setup

### Configuration

Aufi takes its configuration from either the environment variable `EDN_CONFIG`
or a file. In both cases data is expected to be an EDN map as outlined in
`aufi.system.config` with the minimal configuration being:

```clojure
{:s3 {:bucket "aufi-production-01"}}
```

AWS credentials are fetched using the [`DefaultAWSCredentialsProviderChain`][chain].

[chain]: http://docs.aws.amazon.com/AWSJavaSDK/latest/javadoc/com/amazonaws/auth/DefaultAWSCredentialsProviderChain.html

### Running the Service

Aufi can be bundled as an Uberjar using:

```
$ lein uberjar
```

Afterwards, it can be started directly, e.g.:

```
$ java -Xmx256m -Xms256m -jar aufi-standalone.jar "config.edn"
09.08.2016 17:47:54.705 [sfys] [aufi.system.httpd] INFO  - server is running on 0:9876 ...
...
```

Alternatively, the service can be run from the REPL:

```clojure
(require '[aufi.core :as aufi])
(aufi/start "config.edn")
```

In both cases, if the config file is omitted (or given as `nil`) the
environment variable `EDN_CONFIG` will be accessed.

### Logging

Aufi uses [SLF4J][slf4j] for logging and includes [Logback][logback] as its
implementation. An example of a configuration file can be found at
`resources/logback-dev.xml`.

Alternatively, you can replace Logback with an SLF4J-compatible logger of your
choice.

[slf4j]: http://www.slf4j.org/
[logback]: http://logback.qos.ch/

## Usage

### Upload

Images can be uploaded using a `POST` request to `/v1/images`. Image data should
be sent in the request body without any preprocessing or extra encoding; cf.
curl's `-d` and `--data-binary` options.

    $ curl -i http://localhost:9876/v1/images --data-binary @image.jpg
    HTTP/1.1 100 Continue

    HTTP/1.1 201 Created
    Access-Control-Allow-Credentials: true
    Access-Control-Allow-Origin: *
    Access-Control-Expose-Headers: Location
    Date: Tue, 09 Aug 2016 16:18:21 GMT
    Location: /v1/images/e7cb0532-98f1-4d57-825f-c66765d3a4a6
    Server: Aleph/0.4.1
    Content-Length: 0
    Connection: keep-alive

The uploaded image is now available at the path returned in the `Location`
header.

### Download

Aufi offers the following query parameters to control scaling of the requested
image:

  - `max-width`
  - `max-height`
  - `width`
  - `height`

They can be combined to achieve the following results:

<table style='text-align: center'>
  <tr>
    <td/>
    <td>`max-height`</td>
    <td>`height`</td>
    <td>none</td>
  </tr>
  <tr>
    <td>`max-width`</td>
    <td>scale into width/height bounds</td>
    <td>⚡</td>
    <td>scale _down_ to maximum width</td>
  </tr>
  <tr>
    <td>`width`</td>
    <td>⚡</td>
    <td>crop centrally to exact dimensions</td>
    <td>scale _exactly_ to given width</td>
  </tr>
  <tr>
    <td>none</td>
    <td>scale _down_ to maximum height</td>
    <td>scale _exactly_ to given height</td>
    <td>original image</td>
  </tr>
</table>

### Filenames

Aufi allows you to add any kind of path after the image location, so the
following URIs point at the same image:

```
/v1/images/e7cb0532-98f1-4d57-825f-c66765d3a4a6
/v1/images/e7cb0532-98f1-4d57-825f-c66765d3a4a6/cat-trapped-in-monad
```

This can be useful to tackle e.g. some SEO requirements.

### Health Check

The service health can be checked via one of the following paths:

  - `/_status?timeout=N`
  - `/_status`

The default timeout is `1000`, representing 1000ms.

## Developing and Contributing

Contributions are always welcome. Please take a look at our [Contribution
Guidelines](CONTRIBUTING.md) for a quick overview of how your changes can best
make it to master.

## License

Copyright &copy; 2016 stylefruits GmbH

This project is licensed under the [Apache License 2.0][license].

[license]: http://www.apache.org/licenses/LICENSE-2.0.html
