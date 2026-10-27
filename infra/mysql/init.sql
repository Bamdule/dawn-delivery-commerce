-- 서비스마다 DB를 따로 둔다. 서비스 사이를 한 트랜잭션으로 묶지 않는다는 설계를 실행 환경에서도 지킨다
CREATE DATABASE IF NOT EXISTS order_db;
CREATE DATABASE IF NOT EXISTS inventory_db;
CREATE DATABASE IF NOT EXISTS payment_db;
CREATE DATABASE IF NOT EXISTS fulfillment_db;

GRANT ALL PRIVILEGES ON order_db.* TO 'dawn'@'%';
GRANT ALL PRIVILEGES ON inventory_db.* TO 'dawn'@'%';
GRANT ALL PRIVILEGES ON payment_db.* TO 'dawn'@'%';
GRANT ALL PRIVILEGES ON fulfillment_db.* TO 'dawn'@'%';
