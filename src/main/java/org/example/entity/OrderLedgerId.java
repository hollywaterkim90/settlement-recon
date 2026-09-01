package org.example.entity;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

// 원장의 복합키. @IdClass 방식이라 별도 클래스로 분리한다.
// JPA 규칙: no-arg 생성자 + equals/hashCode + Serializable 필수.
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class OrderLedgerId implements Serializable {
    private String channel;
    private String orderId;
}
