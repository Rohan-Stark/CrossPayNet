@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "common",
                "common :: money",
                "payment :: contracts",
                "banking :: contracts",
                "ledger :: contracts",
                "events :: contracts",
                "messaging :: contracts"
        }
)
package com.crosspaynet.operations;
