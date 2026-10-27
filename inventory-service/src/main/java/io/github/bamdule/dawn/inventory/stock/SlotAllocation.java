package io.github.bamdule.dawn.inventory.stock;

// 슬롯 상품의 예약이 어느 슬롯에서 몇 개를 뺐는지. 되돌릴 때 이 내역대로 돌려놓는다
public record SlotAllocation(int slotNo, int quantity) {
}
