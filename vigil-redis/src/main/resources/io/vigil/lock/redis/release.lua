local current_token = redis.call('HGET', KEYS[1], 'token')
if current_token ~= ARGV[1] then
    return 0
end
redis.call('DEL', KEYS[1])
return 1
