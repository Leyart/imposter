/**
 * Trivial example of returning a specific status code, based on the URI.
 *
 * @author Pete Cornish {@literal <outofcoffee@gmail.com>}
 */

switch (context.uri) {
    case ~/.*bad/:
        // HTTP Status-Code 400: Bad Request.
        respond {
            withStatusCode 400
            immediately()
        }
        return

}

switch (context.params["action"]) {
    case "create":
        // HTTP Status-Code 201: Created.
        respond {
            withStatusCode 201
            immediately()
        }
        break

    case "delete":
        // HTTP Status-Code 204: No Content.
        respond {
            withStatusCode 204
            immediately()
        }
        break

    case "fetch":
        // use a different static response file with the default behaviour
        respond {
            withFile "rest-plugin-data2.json"
            and()
            usingDefaultBehaviour()
        }
        break

    default:
        // default to static file in config
        respond {
            usingDefaultBehaviour()
        }
}
