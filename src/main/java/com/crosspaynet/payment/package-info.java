@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {
        "banking",
        "banking :: contracts",
        "common",
        "common :: money"
    }
)
package com.crosspaynet.payment;
