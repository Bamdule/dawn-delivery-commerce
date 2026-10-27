package io.github.bamdule.dawn.fulfillment.target;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

// 주문 서비스의 결제 완료 목록과 주문 상세. 대상 확정이 원천과 대조할 때만 부른다
public interface OrderClient {

    PaidPage paidOrders(LocalDateTime cutoffAt, LocalDateTime cursorPaidAt, Long cursorId, int size);

    Optional<OrderDetail> detail(String orderNo);

    // 마지막 페이지면 다음 커서가 비어 있다
    record PaidPage(List<String> orderNos, LocalDateTime nextCursorPaidAt, Long nextCursorId) {

        public boolean last() {
            return nextCursorId == null;
        }
    }

    record OrderDetail(String orderNo, String status, String recipientName, String address, String regionCode,
                       LocalDateTime paidAt, List<Item> items) {
    }

    record Item(long productId, int quantity, int cancelledQuantity) {
    }
}
