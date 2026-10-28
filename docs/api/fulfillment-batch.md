# fulfillment-batch API

컨트롤러 테스트가 만든 문서입니다. 직접 고치지 말고 `./gradlew :fulfillment-batch:apiDocs` 로 다시 만듭니다.

## 출고 예외 이월

예외 수량을 대기 수량으로 돌린다. 배정 회차는 그대로 둬서 다음 회차의 대상 확정이 가져간다. OPEN 이 아니면 409

**요청**

```http
POST /admin/shipment-exceptions/3/defer HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `exceptionNo` | 출고 예외 번호 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 출고 예외 관리자 취소

예외 수량을 취소 수량으로 옮기고 EXCEPTION_CANCELLED 를 낸다. 주문 서비스가 refund-{주문번호}-admin-{예외 번호} 로 환불한다. OPEN 이 아니면 409

**요청**

```http
POST /admin/shipment-exceptions/4/cancel HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `exceptionNo` | 출고 예외 번호 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 배차 전 출고 지시 취소

잘못 만든 출고 지시를 배차 전에 되돌린다. 실물 재고를 돌려놓고 출고 수량을 예외 수량으로 옮겨 출고 예외(OPEN)를 만든다. CREATED 가 아니면 409

**요청**

```http
POST /admin/shipment-orders/4/cancel HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `shipmentOrderId` | 출고 지시 번호 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 회차 시작

마감 시각으로 출고 배치를 시작한다. 실패한 회차에 다시 부르면 실패한 스텝부터 재시작하고, 완료된 회차는 다시 돌지 않는다. 평소에는 23:10 스케줄러가 부른다

**요청**

```http
POST /admin/cutoff-runs/2090-11-02T23:00/start HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `cutoffAt` | 마감 시각. 예) 2090-12-31T23:00 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 28

{
  "status" : "COMPLETED"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | `String` | 잡 실행 결과. COMPLETED, FAILED 등 Spring Batch 상태 |

## 검증 실패 회차 승인

결과 검증에 실패한 회차(VERIFY_FAILED)를 사람이 원인을 고친 뒤 승인한다. 승인 전에는 배차로 넘기지 않는다. VERIFY_FAILED 가 아니면 409

**요청**

```http
POST /admin/cutoff-runs/2090-11-01T23:00/approve HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `cutoffAt` | 마감 시각 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 대상 확정 차이

대상 확정이 세 번 어긋나 잡을 실패시킬 때 남긴 차이. 사람이 보고 출고 대상을 보정한 뒤 회차를 다시 시작한다

**요청**

```http
GET /admin/cutoff-runs/2090-11-21T23:00/target-diffs HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `cutoffAt` | 마감 시각 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 81

[ {
  "orderNo" : "20901231-F000000012",
  "kind" : "CANCELLED_MARK_BUT_PAID"
} ]
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `[].orderNo` | `String` | 주문 번호 |
| `[].kind` | `String` | MISSING_IN_TARGET, CANCELLED_MARK_BUT_PAID, EXTRA_IN_TARGET |

## 출고 대상 보정

주문 서비스의 주문 상세로 출고 대상을 다시 만든다. 없으면 넣고, 취소 표시인데 결제 완료이고 아직 처리된 수량이 없으면 대기로 되돌린다. 바꿀 것이 없으면 409

**요청**

```http
POST /admin/shipment-targets/20901231-F000000012/resync HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 배송지 수정

주소 오류로 생긴 출고 예외를 이월하기 전에 출고 대상의 배송지를 고친다

**요청**

```http
PUT /admin/shipment-targets/20901231-F000000012/address HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 89
Host: localhost:8080

{
  "recipientName" : "김새벽",
  "address" : "서울시 송파구 올림픽로 300"
}
```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `recipientName` | `String` | 받는 사람 |
| `address` | `String` | 배송지 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 입고

센터 실물 재고를 늘리고 조정 이력과 STOCK_CHANGED 를 한 트랜잭션에 쓴다. 재고 서비스가 판매 가능 재고를 늘린다

**요청**

```http
POST /admin/center-stocks/SEOUL-A/900012/inbound HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 21
Host: localhost:8080

{
  "quantity" : 20
}
```

| 경로 변수 | 설명 |
|---|---|
| `centerId` | 센터 |
| `productId` | 상품 번호 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `quantity` | `Number` | 입고 수량. 1 이상 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 실사

센 수량을 덮어쓰지 않고, 전산 실물과 피킹 전 출고 지시 수량을 뺀 차이만 반영한다. 출고 지시 뒤 피킹 전에 세어도 판매 가능 재고가 늘지 않는다

**요청**

```http
POST /admin/center-stocks/SEOUL-A/900012/stocktake HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 28
Host: localhost:8080

{
  "countedQuantity" : 27
}
```

| 경로 변수 | 설명 |
|---|---|
| `centerId` | 센터 |
| `productId` | 상품 번호 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `countedQuantity` | `Number` | 선반에서 센 수량 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 25

{
  "appliedDelta" : -3
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `appliedDelta` | `Number` | 실물 재고에 실제로 더하거나 뺀 수량. 차이가 없으면 0 |

## 상품별 실물 요약

재고 서비스의 재고 대사가 부른다. 상품마다 센터 실물 합과 출고 전 확정 수량(대기와 예외 수량)을 준다. 한 번에 1,000개까지

**요청**

```http
POST /stocks/summary HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 31
Host: localhost:8080

{
  "productIds" : [ 900011 ]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `productIds` | `Array` | 상품 번호 목록 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 82

[ {
  "productId" : 900011,
  "physicalQuantity" : 10,
  "pendingQuantity" : 2
} ]
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `[].productId` | `Number` | 상품 번호 |
| `[].physicalQuantity` | `Number` | 센터 실물 재고의 합 |
| `[].pendingQuantity` | `Number` | 출고 대상 품목의 대기 수량과 예외 수량의 합 |

## 배차 인계

배차가 VERIFIED 회차를 가져가며 부른다. 그 회차의 출고 지시를 HANDED_OVER 로 바꿔, 실사가 피킹 전 수량으로 빼지 않고 출고 지시 취소도 받지 않게 한다. 다시 부르면 0건이다. VERIFIED 가 아니면 409

**요청**

```http
POST /admin/cutoff-runs/2090-12-01T23:00/hand-over HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `cutoffAt` | 마감 시각 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 22

{
  "handedOver" : 2
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `handedOver` | `Number` | 이번에 넘긴 출고 지시 수 |
