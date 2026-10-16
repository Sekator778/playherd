package crowdvsmarket.bots.port;

/** The in-memory fake against the ports contract. */
class InMemoryPortsTest extends PortsContractTest {

    private final InMemoryExchange exchange = new InMemoryExchange().withBot("rich", 50_000).withBot("broke", 50);

    @Override
    protected MarketDataPort marketData() {
        return exchange;
    }

    @Override
    protected BettingPort betting() {
        return exchange;
    }

    @Override
    protected BotStatePort state() {
        return exchange;
    }

    @Override
    protected String seededBotId() {
        return "rich";
    }

    @Override
    protected String brokeBotId() {
        return "broke";
    }
}
