local current_token = redis.call('HGET', KEYS[1], 'token')
if current_token ~= ARGV[1] then
    return 0
end
local now = tonumber(redis.call('TIME')[1])
local ttl = tonumber(ARGV[2])
redis.call('HSET', KEYS[1], 'expires_at', tostring(now + ttl))
redis.call('EXPIRE', KEYS[1], ttl + 5)
return 1
