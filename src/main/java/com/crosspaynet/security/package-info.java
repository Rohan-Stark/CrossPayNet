@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "banking",
                "banking :: contracts",
                "payment",
                "payment :: contracts",
                "common",
                "common :: money"
        }
)
package com.crosspaynet.security;
