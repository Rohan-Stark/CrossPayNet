@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "banking",
        "banking :: contracts",
        "ledger",
        "payment",
        "payment :: contracts",
        "payment :: settlement",
        "common",
        "common :: money"
    }
)
package com.crosspaynet.settlement;
