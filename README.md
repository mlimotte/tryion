# Tryion

Ions tutorial: https://docs.datomic.com/cloud/ions-tutorial/ions-tutorial.html


* NOTE:  This was a "disposable" AWS account spun up for the purposes of testing tihs technology. That account has since be deleted, so all keys, logins, passwords and account numbers are no longer valid. *


## Details

- Stack names: Tryion
- System name: Tryion
- App Name: tryion
- database-name: tryion

`COMPUTE_GROUP="Tryion-Compute-..."`

# To find IonApiGatewayEndpoint value

Go to AWS Console -> API Gateway -> datomic-{System Name}-ions -> "Invoke URL"

`IonApiGatewayEndpoint="2fttssk6u6.execute-api.us-east-2.amazonaws.com"`


## Datomic Ions Tips

- Download Datomic CLI tools: https://docs.datomic.com/cloud/releases.html

- You can find the available systems from the CLI or the AWS Console. From the CLI, use the datomic cloud list-systems command (https://docs.datomic.com/cloud/operation/cli-tools.html#list-systems)
- To launch in background `nohup [script-command] [script-args] &`
- To delete unused lambdas: If you want to delete these Lambdas anyway, you can push and deploy an application with an empty lambdas map in ion-config.edn.

- If you follow the instructions at https://docs.datomic.com/cloud/ions-tutorial/ions-tutorial.html, under Pre-reqs 1st bullet, follow the steps for "Create Master Stack" and apply the points in bullet #2.  
  - I.e. those first two bullets are ONE step.

The default stack runs on:
t3.small, 2 vCPUs, 2.0 GB, $0.0209/hr

### System info (datomic cli)

    ```shell
    $ export AWS_PROFILE=tryion
    $ datomic system describe-groups Tryion
    $ datomic system list-instances tryion
    ```

    ```json
    [{"name":"Tryion-Compute-GL419NH95X3G",
      "type":"compute",
      "endpoints": [{"type":"client",
                     "api-gateway-endpoint": "https://d3n3x1mv17.execute-api.us-east-2.amazonaws.com",
                     "api-gateway-id":"d3n3x1mv17",
                     "api-gateway-name":"datomic-Tryion-client-api"},
                    {"type":"http-direct",
                    "api-gateway-endpoint": "https://2fttssk6u6.execute-api.us-east-2.amazonaws.com",
                    "api-gateway-id":"2fttssk6u6",
                    "api-gateway-name":"datomic-Tryion-ions"}],
                    "cft-version":"973",
                    "cloud-version":"9132"}]
    ```

### Alt approach to find System Name

```shell
    aws ec2 describe-instances \
       --filters "Name=tag-key,Values=datomic:tx-group" "Name=instance-state-name,Values=running" \
       --query 'Reservations[*].Instances[*].[Tags[?Key==`datomic:system`].Value]' \
       --output text`
```

# Datomic (DB) Schema

As defined in deps.edn:

    clj -X:dev:snapshot-schema-to-local
    clj -X:dev:deploy-schema

# Push and Deploy

N.B. Requires a clean git repo.
The simple way is to use the babashka script:

    # babashka should already be installed and on PATH as `bb`
    ./ionpd

But the individual steps if you want to do it manually are detailed below: 

## Push (copies files to S3)

    clojure -A:ion-dev '{:op :push}'

## Deploy (install on a Datomic compute cloud)

Get the `:deploy-command` output of the push, which will have the correct `:rev` embedded, but it will look like this:

    clojure -A:ion-dev '{:op :deploy, :group tryion-prod-Compute-1VDBJ6SO4CHBK, :rev "..."}'

* Monitor the status, if you see a deployment error, go to:
* AWS Cloudwatch => Log Groups => "datomic-tryion-prod" => "Search log group"
* Then scroll to the bottom or search for "Exceptions".

* Note that ion often returns "failed" for deploy, as in the following, even though it succeeded:

  {:deploy-status "FAILED", :code-deploy-status "SUCCEEDED"}

# Quick Start at REPL

In the `user` namespace.
    
    (def db (d/db @conn))
    
    ;; Run this only once (it is NOT idempotent)
    ;; ;(tryion.db.test-data/create-test-data @conn)

    ;; Sample fn executions 
    (booking/get-booking-by-id (d/db @conn) '[*] 79164837199977)
    
## Mock requests at the REPL

    (def response (tryion.http/app
                (-> (mock/request :get "/tryion/v1/booking/query")
                    (mock/query-string {:customer-external-id "customer1"})
                    (mock/header "Location" "test-01")
                    (mock/header "Authorization" (str "Bearer " (from-system :auth-token))))))
    (-> response :body slurp (json/read-str :key-fn keyword))

## "Allowed" (i.e. transaction functions, attribute preds, etc) invocations

This is a sample use as an attribute predicate.

    ;; Test at REPl
    (functions/valid-sku? "SKU-001")
    (functions/valid-sku? "SKU-001x")
    ;; Install in datomic and test (this works after the Code Deploy (push & deploy) that makes `valid-sku?` available 
    ;(d/transact conn {:tx-data [{:db/ident :asset/sku, :db.attr/preds 'tryion.db.functions/valid-sku?}]})
    ;(def with-db (d/with-db @conn))
    ;(d/with with-db {:tx-data [{:db/id "should-not-work", :asset/sku "not-a-sku"}]})
    (d/transact @conn {:tx-data [{:db/id "should-not-work", :asset/sku "not-a-sku-20"}]})

## Lambda function (with and without arguments)

    aws lambda invoke --function-name ${COMPUTE_GROUP}-get-schema /dev/stdout

Payloads in JSON format:

    aws lambda invoke --cli-binary-format raw-in-base64-out --function-name ${COMPUTE_GROUP}-write-log --payload '{"foo": 1}' /dev/stdout
    # Wait a little bit and then search the "datomic-{System Name}" log group in AWS Console for "write log lambda"

## HTTP Direct

GET

    curl https://${IonApiGatewayEndpoint}/healthcheck

    # Include the auth token, and optionally, the Location headers.
    curl -H"Authorization: Bearer MY_TOKEN" -H "Location: test-01" \
       https://${IonApiGatewayEndpoint}/tryion/v1/booking/query?customer-external-id=customer1

POST

    # Also include the auth and location headers (as above)
    curl -H"Authorization: Bearer MY_TOKEN" -H "Location: test-01" \
       https://${IonApiGatewayEndpoint}/tryion/v1/booking -d :hat
