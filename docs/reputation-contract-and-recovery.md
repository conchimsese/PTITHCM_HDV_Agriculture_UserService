# Phase 6 — Contract và recovery của User

## Phạm vi và quyết định ngày 02/11/2026

User xử lý PENALTY, COMPLETION_REWARD và REVERSAL từ Payment. Payment đã xác nhận reversal trỏ trực tiếp quyết định phạt/thưởng gốc; User đảo actualDelta đã lưu rồi clamp 0–10, không áp requestedDelta để tính điểm. Một gốc chỉ đảo một lần. Không hỗ trợ đảo reversal, tự phục hồi thưởng đã thu hồi hoặc thay đổi trạng thái tiền/cọc.

User chấp thuận đề xuất chuẩn hóa: PENALTY/COMPLETION_REWARD decisionVersion=1; mọi REVERSAL decisionVersion=2, kể cả đảo thưởng. Version không phải sequence chung account/Contract. Payment hiện còn reversal thưởng version=1: **Payment phải sửa producer và bàn giao fixture mới trước khi bật listener**. User reject version=1 của REVERSAL; không tự nâng version trong replay.

Sau reversal đưa score>0: bỏ reputation_lock, giữ status hiện tại, admin_lock, INACTIVE và email verification. Chỉ Admin mới mở ACTIVE qua API hiện có sau khi kiểm các guard. Không phát ACCOUNT_UNLOCKED hoặc public-visible snapshot chỉ vì bỏ reputation_lock. Khi reversal đưa score=0, vẫn khóa/revoke như các quyết định khác.

## Input và dedup

Topic `soa.payment.reputation-decision.v1`, key accountId, group `user-reputation-decision-v1`; sourceService=payment-service, eventType=CONTRACT_REPUTATION_DECIDED, eventVersion=1. Listener mặc định tắt (`REPUTATION_CONSUMER_ENABLED=false`). Fixtures trong docs/fixtures là mẫu phía User để đối chiếu, chưa phải bằng chứng producer Payment thực tế.

`reputation-reversal-penalty.json` và `reputation-reversal-reward.json` tham chiếu các fixture gốc tương ứng, đều version2. `reputation-changed-reversal.json` là mẫu User→Notification trên soa.user.reputation.v1: giả định phạt F04 đã đưa score10→8, reversal đưa8→10 và delta=2. Điểm thực tế luôn phụ thuộc history/current score; fixture không cho phép Notification tính lại điểm từ requestedDelta.

Parser giới hạn 64KiB, reject unknown/duplicate JSON fields, sai UUID/key/source/schema version/type, số không theo bước 0,5 hoặc abs>10, reason/violation không phải mã và reference không khớp ID. Reference hỗ trợ CONTRACT/contractId hoặc ACCOUNT/accountId; fixture dùng CONTRACT. recipientIds là mảng tối đa hai UUID khác nhau, actor/correlation UUID nullable. Giữ kiểm chứng binding envelope/ACL với fixture Payment thật ở Phase 7.

Business key là (decisionId, accountId, action) theo schema BA. Không áp thêm yêu cầu reversal phải có decisionId mới: có thể dùng ID mới hoặc giữ ID gốc với action/version khác; originalDecisionId luôn là liên kết gốc. Không cho hai base action dùng cùng decisionId/account vì sẽ mơ hồ khi tham chiếu. Cùng key khác data/version là conflict. EventId trùng phải giữ nguyên canonical envelope fingerprint; eventId mới và business data giống chỉ thêm receipt, không áp điểm/history/outbox lần hai. Legacy history thiếu fingerprint cần đối soát, không giả lập fingerprint.

Target/counterparty phải tồn tại trong User DB và là Farmer–Trader khác role, không ADMIN. Không query Payment DB hoặc tự kết luận Contract/vi phạm/job72h. F01–F08/T01–T08, F04−2/−3 theo BA. Reward+0,5; cap tổng actualDelta dương đã cấp cho target/counterparty trong 30 ngày xử lý UTC, tối đa 1 điểm. Reversal không hoàn quota; actual0 do cap/ceiling được lưu vĩnh viễn, không cộng lại sau.

## REVERSAL và thứ tự

- Lookup gốc trong history bằng originalDecisionId/accountId và action PENALTY hoặc COMPLETION_REWARD. Kiểm duy nhất một gốc, version=1, khớp contractId/counterpartyId. Không lấy một REVERSAL làm gốc.
- Tính `after = clamp(before - original.actualDelta, 0, 10)`; `actualDelta = after - before`. requestedDelta trong input chỉ lưu để audit, không quyết định mức đảo. Gốc actual0 thì đảo0.
- Account row lock tuần tự hóa mutation; unique index originalDecisionId/account chặn double reversal. Replay cùng reversal là duplicate; reversal khác cho gốc đã đảo là ORIGINAL_ALREADY_REVERSED, chuyển DLT sau retry policy.
- Thiếu gốc là ORIGINAL_DECISION_NOT_FOUND, retry không ACK; gốc là reversal hoặc sai scope/version là permanent conflict. Có thể phục hồi bằng replay gốc được Payment phê chuẩn trước, rồi retry reversal.
- Giữ thứ tự Kafka partition/key; không dùng decisionVersion/effectiveAt để sort account. Reward → reversal → penalty và penalty → reversal → reward được kiểm thử. Opposite base action trên cùng account/contract khi gốc chưa đảo được giữ retry. Một reward contract/account đã dùng không tự tạo lại sau thu hồi.
- Nếu event đến sớm chặn partition, operator dừng listener và phối hợp Payment replay tiền đề theo thứ tự. Không skip offset để tới event gốc ở phía sau. Không claim tự sửa mọi hoán vị lịch sử; correction qua nhiều quyết định cần Payment xác nhận thứ tự/compensation.

## Atomicity và outbox

Transaction lấy advisory lock eventId ở namespace hai-key riêng với media/Admin bigint lock, rồi account row lock; validate/dedup/cap/reversal và ghi history/score/flags/revoke/version/outbox/receipt cùng transaction. V4 bổ sung canonical decision payload/fingerprint/effective_at và bảng event receipt. Không sửa migration đã có.

Score0 đặt reputation_lock, giữ INACTIVE nếu có, còn lại LOCKED; revoke refresh. Chỉ state transition mới có account/visibility event; REPUTATION_CHANGED cho mỗi quyết định áp dụng, kể cả actual0, mang actual delta theo whitelist tám field. Account lock reasonCode=REPUTATION_ZERO; email intent chỉ verified, worker Phase 5 gửi. DB/outbox lỗi rollback toàn bộ. Consumer chỉ ACK sau commit; crash trước ACK nhận lại an toàn nhờ dedup.

## Retry/DLT riêng User

| Lỗi | Xử lý | Offset |
| --- | --- | --- |
| DB/transaction/hạ tầng chưa rõ | Retry 5s vô hạn | Chưa ACK |
| Thiếu account/counterparty/gốc; thứ tự chưa giải quyết | Retry 5s, operator/Payment xử lý | Chưa ACK, không DLT rồi bỏ qua |
| Invalid envelope/policy/version/scope/business conflict/double reversal | Lần đầu +2 retry 1s, forward DLT | Commit sau broker ACK DLT |
| DLT forward lỗi | Recovery thất bại, thử lại | Chưa commit nguồn |

DLT `soa.user.reputation-decision.dlt.v1` do User quản lý, giữ raw value/key và chỉ thêm user-original-topic/partition/offset, user-error-code; không copy headers tùy ý hoặc stack trace. DLT có thể chứa dữ liệu invalid: ACL hạn chế, không cho Notification/Trading/client subscribe. Đề xuất retention riêng 14 ngày; broker owner xác nhận retention nguồn/DLT, ACL, lag monitoring trước vận hành. Producer error listener chỉ log topic, không key/value/header. Không dùng lịch/topic retry Notification.

Record retry có thể chặn các partition cùng consumer thread. Không tự nâng offset, xóa history/receipt hoặc chỉnh score để ép qua. Kafka production cần retention đủ cho lag/recovery; test local không chứng minh hạ tầng production.

## Replay có kiểm soát

1. Dừng listener; lưu topic/partition/offset; phối hợp Payment xác nhận nguyên nhân, tiền đề và thứ tự replay. Không tự tạo quyết định User.
2. Xuất envelope đã được Payment phê chuẩn vào JSONL riêng ngoài webroot, mỗi dòng một envelope; giữ identity/data gốc, không commit dữ liệu thật hoặc log payload. Nếu producer gửi sai version, Payment phải cung cấp event sửa hợp lệ, không sửa JSON tùy ý.
3. Chạy với listener=false, REPUTATION_REPLAY_ENABLED=true, REPUTATION_REPLAY_FILE=<private-jsonl-path>. Giới hạn 64MiB/1000 records, cùng parser/domain/receipt, commit từng record. Batch lỗi có thể đã commit dòng trước, rerun an toàn nhờ dedup.
4. Đối chiếu scripts/Reputation-Status.sql; trả replay-enabled=false rồi restart bình thường. Không thay offset nguồn trước đối soát. Reversal thiếu gốc chỉ chạy được sau khi gốc đã được replay hợp lệ.

Job không tự chữa lịch sử clamp sai do thứ tự trước đó; cần Payment compensation, không rewrite history. Khi triển khai Phase 7, đối chiếu fixture producer mới cho reversal thưởng version2, envelope/key/order thực tế, ACL/retention và User→Notification trước khi bật listener. Code Phase 6 hoàn thành không đồng nghĩa đã nghiệm thu tích hợp Payment production.
