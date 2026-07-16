local lock_token = redis.call('HGET', KEYS[1], 'token')
if (not lock_token) or lock_token ~= ARGV[1] then
    return 0
end
local stages = redis.call('SMEMBERS', KEYS[2])
for _, stage in ipairs(stages) do
    redis.call('DEL', 'vigil:ckpt:' .. ARGV[2] .. ':' .. ARGV[3] .. ':' .. stage)
end
redis.call('DEL', KEYS[2])
return #stages
