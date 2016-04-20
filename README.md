# Imposter: A scriptable, multipurpose mock server [![Build Status](https://travis-ci.org/outofcoffee/imposter.svg?branch=master)](https://travis-ci.org/outofcoffee/imposter)

Decouple your integration tests from the cloud/various back-end systems. Imposter is a mock server with a suite of
plugins. Respond using static files, or write Groovy scripts to customise its behaviour. For
maximum control, you can write your own plugins.

## Plugins

Imposter supports different mock server types using plugins:

* rest - Simple REST API mock.
* sfdc - Basic Salesforce mock implementation.
* hbase - Basic HBase mock implementation.

## Example

     java -jar distro/build/libs/imposter.jar \
            -Dcom.gatehill.imposter.plugin=com.gatehill.imposter.plugin.rest.RestPluginImpl \
            -Dcom.gatehill.imposter.configDir=./plugin/rest/src/test/resources/config \
            -Dcom.gatehill.imposter.listenPort=8080

This starts a mock server using the simple REST plugin. Responses are served in line with the configuration files
inside the `config` folder. With the example above, you can hit the URL
[http://localhost:8080/example](http://localhost:8080/example) to see the mock response.

# Getting started

Note: See the _Usage_ section for the required arguments, and the examples below.

## Docker container

The easiest way to get started is to use the Docker container:

    docker run --ti -p 8443 outofcoffee/imposter [args]

## Java

If Docker isn't your thing, or you want to build Imposter yourself, you can create a standlone
JAR file. See the _Build_ section below.

# Usage

The following system properties can be used (specify as command line switches with `-Dswitch=value`). 

    com.gatehill.imposter.plugin            Plugin class name
    com.gatehill.imposter.configDir         Directory containing mock configuration files
    com.gatehill.imposter.host              Host to which to bind when listening
    com.gatehill.imposter.listenPort        Port on which to listen
    com.gatehill.imposter.serverUrl         Explicitly set the server address, e.g. http://mypublicserver:8443
    com.gatehill.imposter.tls               Whether TLS/SSL is enabled
    com.gatehill.imposter.keyStorePath      Path to keystore
    com.gatehill.imposter.keyStorePassword  Keystore password

# Plugin examples

## rest

Simple REST API mock.

Example:

     java -jar distro/build/libs/imposter.jar \
            -Dcom.gatehill.imposter.plugin=com.gatehill.imposter.plugin.rest.RestPluginImpl \
            -Dcom.gatehill.imposter.configDir=./plugin/rest/src/test/resources/config

## sfdc

Basic Salesforce mock implementation. Supports non-persistent:

* SObject creation
* SObject update
* SObject retrieval by ID
* Dummy SOQL queries

**Note:** Clients interacting with this plugin usually requires TLS/SSL to be enabled. 
Ensure that you use an _https://_ scheme for accessing the mock server.

Example:

     java -jar distro/build/libs/imposter.jar \
            -Dcom.gatehill.imposter.plugin=com.gatehill.imposter.plugin.sfdc.SfdcPluginImpl \
            -Dcom.gatehill.imposter.configDir=./plugin/sfdc/src/test/resources/config \
            -Dcom.gatehill.imposter.tls=true \
            -Dcom.gatehill.imposter.keystorePath=./server/src/main/resources/keystore/ssl.jks \
            -Dcom.gatehill.imposter.keystorePassword=password

**Note:** This uses a self-signed certificate for TLS/SSL. You can also choose your own keystore.
If you need to trust the self-signed certificate when using the default, the keystore is located at
`server/src/main/resources/keystore` and uses the secure password 'password'.

## hbase

Basic HBase mock implementation. Uses protobuf for wire transport. Supports dummy Scanner queries and individual 
row/record retrieval.

Example:

     java -jar distro/build/libs/imposter.jar \
            -Dcom.gatehill.imposter.plugin=com.gatehill.imposter.plugin.hbase.HBasePluginImpl \
            -Dcom.gatehill.imposter.configDir=./plugin/hbase/src/test/resources/config

**Note:** This plugin will use the server URL in the `Location` header of the scanner creation response. You might
want to consider setting the `serverUrl` property explicitly to the publicly-accessible address of the mock server.

# Configuration

## Basics

Imposter configuration files must be named with a `-config.json` suffix. For example: `mydata-config.json`.

For example:

    {
      "plugin": "com.gatehill.imposter.plugin.rest.RestPluginImpl",
      "basePath": "/example",
      "response": {
        "staticFile": "example-data.json"
      }
    }

You **must** specify the plugin to use in the configuration file. See the examples in this document for possible values.

## Simple (static response files)

You can control Imposter's responses using static response files. Use the `staticFile` property
within the `response` object in your configuration.

Response files can be named anything you like and are resolved relative to the configuration directory.

In the example above, we are using a static response file (`example-data.json`):

     {
       "hello": "world"
     }

## Advanced (scripting)

You can also control Imposter's responses using [Groovy](http://groovy-lang.org/) scripts. Since it's Groovy, you
can write plain Java in your scripts as well.

Here's an example configuration file that uses a script:

    {
      "plugin": "com.gatehill.imposter.plugin.rest.RestPluginImpl",
      "basePath": "/scripted",
      "response": {
        "scriptFile": "example.groovy"
      }
    }

Here's the corresponding script (`example.groovy`):

    switch (context.params["action"]) {
        case "create":
            // HTTP Status-Code 201: Created.
            respond() withStatusCode 201 immediately()
            break

        case "fetch":
            // use a different static response file with the default behaviour
            respond() withFile "static-data.json" and() withDefaultBehaviour()
            break

        default:
            // default to bad request
            respond() withStatusCode 400 immediately()
    }

In this example, the script causes the mock server to respond with HTTP status codes 200, 201 or 400 depending on
the value of the `action` parameter in the request.

For example:

    HTTP GET http://localhost:8443/scripted?action=201
    ...
    201 Created

    HTTP GET http://localhost:8443/scripted?action=foo
    ...
    400 Bad Request

In the case of `action=fetch`, the script causes the mock server to use the content of the static file
`static-data.json` to serve the response.

## The context object

The `context` object in the example above is holds things you might like to interrogate,
like request parameters or the absolute URI of the request.

Certain plugins will add additional properties to the `context`, for example, the _hbase_
plugin provides a `responsePhase` property of type `com.gatehill.imposter.plugin.hbase.model.ResponsePhase`
that you can use to determine the type of request.

## The ResponseBehaviour object

Your scripts are a subclass of `com.gatehill.imposter.model.ResponseBehaviour`.

The ResponseBehaviour class provides a number of methods to enable you to control the mock server response:
 
| Method | Description
| --- | ---
| `withStatusCode(int)`    | Set the HTTP status code for the response
| `withFile()`             | Respond with the content of a static file
| `withEmpty()`            | Respond with empty content, or no records
| `withDefaultBehaviour()` | Use the plugin's default behaviour to respond
| `immedately()`           | Skip the plugin's default behaviour and respond immediately
| `and()`                  | Syntactic sugar to improve readability of `respond` statements

## Returning data

To do this, you must either:

1. set the `staticFile` property within the `response` object in your configuration, or
2. call the `ResponseBehaviour.withFile(String)` in your script.

Here's an example of the static file approach (1):

    {
      "plugin": "com.gatehill.imposter.plugin.rest.RestPluginImpl",
      "basePath": "/scripted",
      "response": {
        "scriptFile": "example.groovy",
        "staticFile": "example-data.json"
      },
      "contentType": "application/json"
    }

In this case, the static file `example-data.json` will be used if the script does not
invoke the `ResponseBehaviour.withFile(String)` method with a different filename.

In order for the mock server to return the response file in an appropriate format,
your script should invoke `ResponseBehaviour.withDefaultBehaviour()`.
See the *rest* plugin tests for a working example.

# Tips and tricks

## Waiting for the server to be ready

The mock server exposes an endpoint at `/system/status` that will return HTTP 200 when the mock server is up and running.
You can use this to let your tests know when the mock server is ready.

## JUnit integration

You can make use of Imposter mocks in your [JUnit](http://junit.org) tests using the excellent 
[testcontainers](http://testcontainers.org) library. This will enable your mocks to start/stop before/after your
tests run.

Here's a simple overview:

1. Create a simple _Dockerfile_ that extends `outofcoffee/imposter` and adds your desired properties as its `CMD`.
2. Build an image from your _Dockerfile_.
3. Follow the _testcontainers_ 'getting started' documentation for your project.
4. Create your mock configuration and mock data and place it in project (e.g. under `src/test/resources`).
5. Add a `GenericContainer` _testcontainers_ class rule to your JUnit test.
6. Configure your `GenericContainer` to wait for the `/system/status` HTTP endpoint to be accessible so your tests don't start before the mock is ready.
7. Configure your `GenericContainer` to mount the directory containing your configuration and data to `/opt/imposter/config`.
 
Now, when you run your test, your custom mock container will start, load your configuration and mock data, ready
for your test methods to use it!

# Build

## Prerequisites

* JDK 8

## Steps

For distribution, Imposter is built as a 'fat JAR' (aka 'shadow JAR'). To get started with the examples here, first run:

    ./gradlew clean shadowJar
    
The JAR is created under the `distro/build/libs` directory.

If, instead, you want to compile the JAR without embedded dependencies, use:

    ./gradlew clean build

## Tests

If you want to run tests:

    ./gradlew clean test

## Docker container

Build the Docker container with:

    docker build --tag outofcoffee/imposter .

# TODO

* HBase content negotiation
* HBase response content type header
* API specification import (e.g. Swagger)

# Contributing

Pull requests are welcome.

# Author

Pete Cornish (outofcoffee@gmail.com)
