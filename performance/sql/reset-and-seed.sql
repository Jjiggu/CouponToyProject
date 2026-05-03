SET @member_count = 100000;
SET @coupon_total_count = 10000;

DELETE FROM coupon_issues;
DELETE FROM coupons;
DELETE FROM members;

ALTER TABLE coupon_issues AUTO_INCREMENT = 1;
ALTER TABLE coupons AUTO_INCREMENT = 1;
ALTER TABLE members AUTO_INCREMENT = 1;

INSERT INTO coupons (created_at, updated_at, name, total_count, issued_count)
VALUES
    (NOW(), NOW(), 'perf-pessimistic', @coupon_total_count, 0),
    (NOW(), NOW(), 'perf-redis-transactional', @coupon_total_count, 0),
    (NOW(), NOW(), 'perf-redis-event', @coupon_total_count, 0);

DROP PROCEDURE IF EXISTS seed_performance_members;

DELIMITER //
CREATE PROCEDURE seed_performance_members(IN target_count INT)
BEGIN
    DECLARE current_id INT DEFAULT 1;

    START TRANSACTION;
    WHILE current_id <= target_count DO
        INSERT INTO members (email, password, name, role)
        VALUES (
            CONCAT('perf', current_id, '@example.com'),
            'not-used-for-jwt-performance-test',
            CONCAT('perf', current_id),
            'MEMBER'
        );

        SET current_id = current_id + 1;
    END WHILE;
    COMMIT;
END//
DELIMITER ;

CALL seed_performance_members(@member_count);

DROP PROCEDURE IF EXISTS seed_performance_members;

SELECT coupon_id, name, total_count, issued_count
FROM coupons
ORDER BY coupon_id;
