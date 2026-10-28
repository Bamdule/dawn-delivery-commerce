rootProject.name = "dawn-delivery-commerce"

include(
    "common-event",
    "messaging",
    "order-service",
    "inventory-service",
    "payment-service",
    "fake-pg",
    "fulfillment-batch",
    "system-experiment",
)
