@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "common",
                "common :: money",
                "payment :: contracts",
                "banking :: contracts",
                "ledger",
                "events",
                "events :: contracts",
                "messaging :: contracts"
        }
)
package com.crosspaynet.operations;
