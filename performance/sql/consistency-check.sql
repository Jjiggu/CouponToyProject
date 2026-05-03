SET @coupon_id = 1;
SET @redis_scard = 0;

SELECT
    c.coupon_id,
    c.name,
    c.total_count,
    c.issued_count,
    COUNT(ci.issued_id) AS issue_rows,
    COUNT(DISTINCT ci.member_id) AS distinct_members,
    CASE
        WHEN COUNT(ci.issued_id) <= c.total_count
            AND COUNT(ci.issued_id) = COUNT(DISTINCT ci.member_id)
            AND c.issued_count = COUNT(ci.issued_id)
        THEN 'PASS'
        ELSE 'FAIL'
    END AS db_consistency
FROM coupons c
LEFT JOIN coupon_issues ci ON ci.coupon_id = c.coupon_id
WHERE c.coupon_id = @coupon_id
GROUP BY c.coupon_id, c.name, c.total_count, c.issued_count;

SELECT
    coupon_id,
    member_id,
    COUNT(*) AS duplicate_count
FROM coupon_issues
WHERE coupon_id = @coupon_id
GROUP BY coupon_id, member_id
HAVING COUNT(*) > 1;

SELECT
    @coupon_id AS coupon_id,
    @redis_scard AS redis_scard_from_manual_input,
    COUNT(*) AS issue_rows,
    CASE
        WHEN @redis_scard = 0 THEN 'SET @redis_scard to Redis SCARD result for Redis strategies'
        WHEN @redis_scard = COUNT(*) THEN 'PASS'
        ELSE 'FAIL'
    END AS redis_db_consistency
FROM coupon_issues
WHERE coupon_id = @coupon_id;
