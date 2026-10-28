# inventory-service API

컨트롤러 테스트가 만든 문서입니다. 직접 고치지 말고 `./gradlew :inventory-service:apiDocs` 로 다시 만듭니다.

## 품절 목록

판매 가능 재고가 0 인 상품. 주문 서비스가 500ms 마다 받아 주문을 저장하기 전에 특가 품절 시도를 거른다. 요청 때마다 계산한다

**요청**

```http
GET /stocks/sold-out HTTP/1.1
Host: localhost:8080

```

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 63

{
  "productIds" : [ 100002, 500003, 500004, 500010, 500018 ]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `productIds` | `Array` | 품절 상품 번호 목록 |

## 예약

품목마다 판매 가능 재고를 조건부로 차감하고 HELD 예약을 만든다. 같은 주문 번호로 다시 오면 새로 잡지 않고 기존 만료 시각을 돌려준다. 재고가 모자라거나 판매 제한 권역이면 409 와 함께 그 상품 번호를 돌려준다

**요청**

```http
POST /reservations HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 187
Host: localhost:8080

{
  "orderNo" : "20261028-000706-78333",
  "regionCode" : "SEOUL",
  "lines" : [ {
    "productId" : 500014,
    "quantity" : 2
  }, {
    "productId" : 500015,
    "quantity" : 1
  } ]
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `orderNo` | `String` | 주문 번호 |
| `regionCode` | `String` | 배송 권역. 판매 제한 권역이면 품절로 답한다 |
| `lines[].productId` | `Number` | 상품 번호 |
| `lines[].quantity` | `Number` | 수량 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 44

{
  "expiresAt" : "2026-10-29T08:48:38.99"
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `expiresAt` | `String` | 예약 만료 시각. 품목 중 가장 짧은 유지 시간으로 하나다. 예시는 특가 상품이 섞여 3분이다 |

## 결제 시작 표시

만료 전의 HELD 를 PAYING 으로 바꾸고 결제 회차를 적는다. 0행이면 marked 가 거짓이고 지금 상태로 주문 서비스가 갈래를 나눈다. 예약이 없으면 404

**요청**

```http
POST /reservations/20261028-000707-38958/paying HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 24
Host: localhost:8080

{
  "paymentRound" : 1
}
```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `paymentRound` | `Number` | 이번 결제 회차 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 137

{
  "marked" : true,
  "currentStatus" : "PAYING",
  "currentRound" : 1,
  "expiresAt" : "2026-10-29T08:55:39.086",
  "expired" : false
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `marked` | `Boolean` | 이번 요청으로 PAYING 이 됐는지 |
| `currentStatus` | `String` | 지금 예약 상태. [HELD, PAYING, CONFIRMED, RELEASED, RESTORED] |
| `currentRound` | `Number` | 예약에 적힌 결제 회차 |
| `expiresAt` | `String` | 만료 시각 |
| `expired` | `Boolean` | 재고 DB 시계로 만료가 지났는지 |

## 거절 되돌림

카드가 거절된 회차의 PAYING 을 HELD 로 되돌린다. 그 회차일 때만 바뀌어, 늦게 온 앞 회차의 되돌림이 다음 회차를 건드리지 않는다

**요청**

```http
POST /reservations/20261028-000709-88583/paying/1/revert HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |
| `paymentRound` | 거절된 결제 회차 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 23

{
  "reverted" : true
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `reverted` | `Boolean` | 이번 요청으로 HELD 가 됐는지 |

## 예약 상태

주문 서비스의 정리 배치가 만료된 PENDING 주문을 닫을지 정할 때 쓴다. 예약이 없으면 404

**요청**

```http
GET /reservations/20261028-000708-97166 HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `orderNo` | 주문 번호 |

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 111

{
  "status" : "PAYING",
  "paymentRound" : 2,
  "expiresAt" : "2026-10-29T08:55:39.109",
  "expired" : false
}
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `status` | `String` | 예약 상태. [HELD, PAYING, CONFIRMED, RELEASED, RESTORED] |
| `paymentRound` | `Number` | 결제 회차. 결제를 시작하기 전에는 없다 (없을 수 있음) |
| `expiresAt` | `String` | 만료 시각 |
| `expired` | `Boolean` | 재고 DB 시계로 만료가 지났는지 |

## 판매 제한 권역 설정 (관리)

그 권역의 주문을 품절로 답하게 한다. 같은 상품과 권역의 권역 불일치가 사흘 연속이면 운영자가 쓴다. 해제는 같은 주소에 DELETE

**요청**

```http
PUT /admin/stocks/500013/blocked-regions/SEOUL HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `productId` | 상품 번호 |
| `regionCode` | 배송 권역 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 슬롯으로 펴기 (관리)

특가 판매 전에 재고를 슬롯 8개로 나눈다. 합쳐진(MERGED) 상품도 다시 편다

**요청**

```http
PUT /admin/stocks/500017/slot-mode/spread HTTP/1.1
Host: localhost:8080

```

| 경로 변수 | 설명 |
|---|---|
| `productId` | 상품 번호 |

**응답**

```http
HTTP/1.1 204 No Content

```

## 재고 대사 불일치 (관리)

10분 간격 두 번 연속 대사 식이 어긋난 상품. 사람이 실물을 확인한 뒤 보정한다

**요청**

```http
GET /admin/stock-reconciliations HTTP/1.1
Host: localhost:8080

```

**응답**

```http
HTTP/1.1 200 OK
Content-Type: application/json
Content-Length: 114

[ {
  "productId" : 500016,
  "leftSide" : 10,
  "rightSide" : 12,
  "firstSeenAt" : "2026-10-29T08:34:39.043"
} ]
```

| 필드 | 타입 | 설명 |
|---|---|---|
| `[].productId` | `Number` | 상품 번호 |
| `[].leftSide` | `Number` | 판매 가능 재고 - 부족분 + 잡힌 예약 + 출고 전 확정 수량 |
| `[].rightSide` | `Number` | 센터 실물 합 |
| `[].firstSeenAt` | `String` | 처음 어긋난 시각 |

## 판매 가능 재고 보정 (관리)

판매 가능 재고를 더하거나 뺀다. 늘리는 쪽은 부족분부터 갚고, 줄이는 쪽은 0 아래로 갈 몫을 부족분에 둔다. 열린 대사 기록을 닫는다

**요청**

```http
POST /admin/stocks/500016/corrections HTTP/1.1
Content-Type: application/json;charset=UTF-8
Content-Length: 17
Host: localhost:8080

{
  "delta" : 2
}
```

| 경로 변수 | 설명 |
|---|---|
| `productId` | 상품 번호 |

| 필드 | 타입 | 설명 |
|---|---|---|
| `delta` | `Number` | 더하거나 뺄 수량. 대사의 오른쪽 - 왼쪽을 넣으면 식이 맞는다 |

**응답**

```http
HTTP/1.1 204 No Content

```
