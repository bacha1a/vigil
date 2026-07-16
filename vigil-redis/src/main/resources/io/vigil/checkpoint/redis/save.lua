local lock_token = redis.call('HGET', KEYS[1], 'token')
if (not lock_token) or lock_token ~= ARGV[1] then
    return 0
end
local existing = redis.call('HGET', KEYS[2], 'fencing_token')
if existing and tonumber(existing) > tonumber(ARGV[1]) then
    return 1
end
redis.call('HSET', KEYS[2],
    'job_name',      ARGV[6],
    'run_id',        ARGV[7],
    'stage_name',    ARGV[8],
    'status',        ARGV[2],
    'stored_value',  ARGV[3],
    'value_type',    ARGV[4],
    'sv_null',       ARGV[9],
    'fencing_token', ARGV[1],
    'updated_at',    ARGV[5])
redis.call('SADD', KEYS[3], ARGV[8])
redis.call('SADD', KEYS[4], ARGV[8])
return 1
