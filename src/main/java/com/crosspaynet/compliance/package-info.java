@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
                "payment",
                "payment :: contracts",
                "common",
                "common :: money"
        }
)
package com.crosspaynet.compliance;
