-- wrk2 script of the bench (design §3 S-C): POST /api/rpc/<method> with a seeded session cookie, constant throughput (-R).
--   wrk -t8 -c256 -d120s -R 20000 --latency -s herd.lua http://server:8081 -- <cookies.tsv> <sessions> <mode>
-- mode: mix (markets / priceSeries / myPositions in thirds, a tab's poll), markets, serverInfo.
-- Each wrk thread reads the first <sessions> cookies from the file (the TSV written by seed.sh) and picks one at random per request.
local cookies, n, mode = {}, 0, "mix"
local counter = 0
local threads = {}

function setup(thread)
  thread:set("tid", #threads)
  table.insert(threads, thread)
end

function init(args)
  local path, want = args[1] or "/results/cookies.tsv", tonumber(args[2] or "1000")
  mode = args[3] or "mix"
  math.randomseed(os.time() * 1000 + (tid or 0) * 7919)
  for line in io.lines(path) do
    local c = line:match("^([^\t]+)")
    if c and c:sub(1, 1) ~= "#" then
      n = n + 1
      cookies[n] = "herd_session=" .. c
      if n >= want then break end
    end
  end
  assert(n > 0, "no cookies in " .. path)
  local function req(method, body)
    return function(cookie)
      return wrk.format("POST", "/api/rpc/" .. method,
        { ["Content-Type"] = "application/json", ["Accept"] = "application/json", ["Cookie"] = cookie }, body)
    end
  end
  reqs = {
    markets = req("markets", '{"args":[]}'),
    priceSeries = req("priceSeries", '{"args":["BTC"]}'),
    myPositions = req("myPositions", '{"args":[]}'),
    serverInfo = req("serverInfo", '{"args":[]}'),
  }
  order = { "markets", "priceSeries", "myPositions" }
end

function request()
  local m
  if mode == "mix" then
    counter = counter + 1
    m = order[(counter % 3) + 1]
  else
    m = mode
  end
  return reqs[m](cookies[math.random(n)])
end

local bad = 0
function response(status, headers, body)
  if status ~= 200 then bad = bad + 1 end
end

function done(summary, latency, requests)
  io.write(string.format("herd.lua: non200=%d connect=%d read=%d write=%d timeout=%d\n", summary.errors.status, summary.errors.connect, summary.errors.read, summary.errors.write, summary.errors.timeout))
  io.write("HDR percentiles (us, from the scheduled send time, coordinated-omission corrected):\n")
  for _, p in pairs({ 50, 75, 90, 99, 99.9, 99.99, 99.999, 100 }) do
    io.write(string.format("  p%-7s %10.0f us\n", p, latency:percentile(p)))
  end
  io.write(string.format("  requests=%d duration_us=%d bytes=%d\n", summary.requests, summary.duration, summary.bytes))
end
