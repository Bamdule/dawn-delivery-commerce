rootProject.name = "dawn-delivery-commerce"

include(
    "common-event",
    "order-service",
    "inventory-service",
    "payment-service",
    "fake-pg",
    "fulfillment-batch",
)
