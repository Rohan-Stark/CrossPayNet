@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "banking",
                "banking :: contracts",
                "payment",
                "payment :: contracts",
                "messaging",
                "messaging :: contracts",
                "settlement",
                "settlement :: contracts",
                "common",
                "common :: money"
        }
)
package com.crosspaynet.events;
