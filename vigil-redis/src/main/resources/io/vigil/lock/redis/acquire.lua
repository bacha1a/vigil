local expires_at = redis.call('HGET', KEYS[1], 'expires_at')
local now        = tonumber(redis.call('TIME')[1])
if expires_at and tonumber(expires_at) > now then
    return {0, nil, nil}
end
local token  = redis.call('INCR', 'vigil:token:' .. KEYS[1])
local run_id = ARGV[3]
redis.call('HMSET', KEYS[1],
    'holder',      ARGV[1],
    'token',       tostring(token),
    'acquired_at', tostring(now),
    'expires_at',  tostring(now + tonumber(ARGV[2])),
    'run_id',      run_id)
redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]) + 5)
return {1, token, run_id}
