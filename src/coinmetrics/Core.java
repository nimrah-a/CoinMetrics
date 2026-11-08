package coinmetrics;

import java.io.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

/** Core domain and networking, intentionally independent of Swing. */
public final class Core {
    private Core() {}
    public record Asset(String id,String symbol,String name,double price,double change,double cap,double volume) {}
    public record Point(long time,double value) {}
    public record Holding(String symbol,BigDecimal quantity,BigDecimal costBasis) {}
    public static final String[] IDS={"btc-bitcoin","eth-ethereum","sol-solana","bnb-binance-coin","xrp-xrp","ada-cardano","doge-dogecoin","avax-avalanche","dot-polkadot","link-chainlink"};
    public static final String[] SYMBOLS={"BTC","ETH","SOL","BNB","XRP","ADA","DOGE","AVAX","DOT","LINK"};
    public static final String[] NAMES={"Bitcoin","Ethereum","Solana","BNB","XRP","Cardano","Dogecoin","Avalanche","Polkadot","Chainlink"};
    public static final double[] DEMO={68500,3450,158,602,0.58,0.46,0.135,29.5,6.2,15.9};
    public static List<Asset> demoAssets(){ List<Asset> r=new ArrayList<>(); double[] changes={2.42,-0.81,4.16,1.18,-1.27,2.01,-0.45,3.08,-0.72,1.61}; for(int i=0;i<IDS.length;i++)r.add(new Asset(IDS[i],SYMBOLS[i],NAMES[i],DEMO[i],changes[i],DEMO[i]*(900_000_000.0/(i+1)),DEMO[i]*300000)); return r; }
    public static List<Point> demoSeries(String symbol,int days){int index=Arrays.asList(SYMBOLS).indexOf(symbol); if(index<0)index=0;double base=DEMO[index],p=base*.86;Random rng=new Random(4917L+index*11+days);List<Point> points=new ArrayList<>();for(int i=0;i<days;i++){p=Math.max(0.0001,p*(1+.003+(rng.nextDouble()-.5)*.065));points.add(new Point(Instant.now().minus(Duration.ofDays(days-i)).toEpochMilli(),p));}return points;}
    public static BigDecimal amount(String raw){try{BigDecimal n=new BigDecimal(raw.trim());if(n.signum()<=0||n.scale()>8||n.compareTo(new BigDecimal("1000000000"))>0)throw new IllegalArgumentException("Enter a positive quantity with up to 8 decimals (max 1 billion).");return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Enter a valid positive asset quantity.");}}
    public static BigDecimal money(String raw){try{BigDecimal n=new BigDecimal(raw.trim());if(n.signum()<0||n.scale()>2||n.compareTo(new BigDecimal("1000000000000"))>0)throw new IllegalArgumentException("Cost basis must be nonnegative (up to 2 decimals).");return n;}catch(NumberFormatException e){throw new IllegalArgumentException("Enter a valid USD cost basis.");}}
    public static BigDecimal value(BigDecimal qty,double price){return qty.multiply(BigDecimal.valueOf(price)).setScale(2,RoundingMode.HALF_UP);}
    public static double percent(double current,double previous){return previous==0?0:(current-previous)/previous*100;}
    public static final class Portfolio {
        private final Map<String,Holding> holdings=new LinkedHashMap<>();
        public synchronized void put(String symbol,BigDecimal qty,BigDecimal cost){if(!Arrays.asList(SYMBOLS).contains(symbol))throw new IllegalArgumentException("Unsupported symbol");if(qty.signum()<=0)throw new IllegalArgumentException("Quantity must be positive");if(cost.signum()<0)throw new IllegalArgumentException("Cost basis must be nonnegative");holdings.put(symbol,new Holding(symbol,qty,cost));}
        /** Record a local hypothetical purchase; cost is the full USD amount paid. */
        public synchronized void buy(String symbol, BigDecimal quantity, BigDecimal usdSpent){
            if(quantity.signum()<=0||usdSpent.signum()<0)throw new IllegalArgumentException("Invalid purchase amount");
            Holding previous=holdings.get(symbol);
            put(symbol,quantity.add(previous==null?BigDecimal.ZERO:previous.quantity()),usdSpent.add(previous==null?BigDecimal.ZERO:previous.costBasis()));
        }
        /** Record a local hypothetical sale using proportional average cost basis; returns realized P/L. */
        public synchronized BigDecimal sell(String symbol,BigDecimal quantity,BigDecimal proceeds){
            if(quantity.signum()<=0||proceeds.signum()<0)throw new IllegalArgumentException("Invalid sale amount");
            Holding h=holdings.get(symbol);
            if(h==null||quantity.compareTo(h.quantity())>0)throw new IllegalArgumentException("Insufficient tracked quantity to record this sale.");
            BigDecimal disposedCost=h.costBasis().multiply(quantity).divide(h.quantity(),2,RoundingMode.HALF_UP);
            BigDecimal remaining=h.quantity().subtract(quantity);
            if(remaining.signum()==0)holdings.remove(symbol);
            else put(symbol,remaining,h.costBasis().subtract(disposedCost));
            return proceeds.subtract(disposedCost);
        }
        public synchronized void remove(String symbol){holdings.remove(symbol);}
        public synchronized List<Holding> all(){return List.copyOf(holdings.values());}
        public synchronized BigDecimal total(Map<String,Asset> prices){BigDecimal sum=BigDecimal.ZERO;for(Holding h:holdings.values()){Asset a=prices.get(h.symbol());if(a!=null)sum=sum.add(value(h.quantity(),a.price()));}return sum;}
        public synchronized BigDecimal invested(){BigDecimal sum=BigDecimal.ZERO;for(Holding h:holdings.values())sum=sum.add(h.costBasis());return sum;}
        public synchronized void save(Path file)throws IOException{Files.createDirectories(file.toAbsolutePath().getParent());List<String> rows=new ArrayList<>();rows.add("# CoinMetrics holdings (symbol,quantity,total_USD_cost_basis)");for(Holding h:holdings.values())rows.add(h.symbol()+","+h.quantity().toPlainString()+","+h.costBasis().toPlainString());Files.write(file,rows,StandardCharsets.UTF_8);}
        public synchronized void load(Path file)throws IOException{if(!Files.exists(file))return;Map<String,Holding> tmp=new LinkedHashMap<>();for(String s:Files.readAllLines(file,StandardCharsets.UTF_8)){if(s.isBlank()||s.startsWith("#"))continue;String[] p=s.split(",",-1);if(p.length!=3)continue;try{BigDecimal q=amount(p[1]),c=money(p[2]);if(Arrays.asList(SYMBOLS).contains(p[0]))tmp.put(p[0],new Holding(p[0],q,c));}catch(RuntimeException ignored){}}holdings.clear();holdings.putAll(tmp);}
    }
    public static final class Api {
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
        private String get(String path)throws Exception{HttpRequest req=HttpRequest.newBuilder(URI.create("https://api.coinpaprika.com/v1/"+path)).timeout(Duration.ofSeconds(12)).header("Accept","application/json").header("User-Agent","CoinMetrics-Portfolio/1.0").build();HttpResponse<String> res=client.send(req,HttpResponse.BodyHandlers.ofString());if(res.statusCode()!=200)throw new IOException("CoinPaprika HTTP "+res.statusCode());return res.body();}
        @SuppressWarnings("unchecked") public List<Asset> quotes()throws Exception{Object data=Json.read(get("tickers?quotes=USD"));if(!(data instanceof List<?> rows))throw new IOException("Unexpected API response");Map<String,Asset> found=new HashMap<>();for(Object obj:rows){if(!(obj instanceof Map<?,?> raw))continue;Map<String,Object> m=(Map<String,Object>)raw;String id=str(m.get("id"));int i=Arrays.asList(IDS).indexOf(id);if(i<0)continue;Object quote=m.get("quotes");if(!(quote instanceof Map<?,?> q))continue;Object usd=q.get("USD");if(!(usd instanceof Map<?,?> rawusd))continue;Map<String,Object> v=(Map<String,Object>)rawusd;double price=num(v.get("price"));if(!Double.isFinite(price)||price<=0)continue;found.put(id,new Asset(id,SYMBOLS[i],NAMES[i],price,num(v.get("percent_change_24h")),num(v.get("market_cap")),num(v.get("volume_24h"))));}if(found.size()<4)throw new IOException("Incomplete market response");List<Asset> result=new ArrayList<>();for(String id:IDS){Asset a=found.get(id);if(a!=null)result.add(a);}return result;}
        @SuppressWarnings("unchecked") public List<Point> history(String id,int days)throws Exception{if(!Arrays.asList(IDS).contains(id))throw new IllegalArgumentException("Unsupported asset");LocalDate start=LocalDate.now(ZoneOffset.UTC).minusDays(days+2);Object data=Json.read(get("tickers/"+id+"/historical?start="+start+"&interval=24h"));if(!(data instanceof List<?> rows))throw new IOException("Unexpected history response");List<Point> result=new ArrayList<>();for(Object raw:rows){if(!(raw instanceof Map<?,?> obj))continue;try{long time=Instant.parse(str(obj.get("timestamp"))).toEpochMilli();double value=num(obj.get("price"));if(Double.isFinite(value)&&value>0)result.add(new Point(time,value));}catch(RuntimeException ignored){}}result.sort(Comparator.comparingLong(Point::time));if(result.size()>days)result=new ArrayList<>(result.subList(result.size()-days,result.size()));if(result.size()<2)throw new IOException("Insufficient historical data");return result;}
        private static String str(Object o){return o==null?"":o.toString();}
        private static double num(Object o){return o instanceof Number?((Number)o).doubleValue():0;}
    }
    /** Small JSON decoder for CoinPaprika responses; avoids runtime dependencies. */
    public static final class Json {
        private final String s;private int i;private Json(String s){this.s=s;}
        public static Object read(String s){Json j=new Json(s);Object o=j.parse();j.ws();if(j.i!=s.length())throw new IllegalArgumentException("Trailing JSON");return o;}
        private void ws(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        private Object parse(){ws();if(i>=s.length())throw new IllegalArgumentException("Unexpected EOF");char c=s.charAt(i);if(c=='{')return object();if(c=='[')return array();if(c=='"')return string();if(c=='t'||c=='f'||c=='n')return literal();return number();}
        private Object object(){i++;Map<String,Object> out=new LinkedHashMap<>();ws();if(consume('}'))return out;do{ws();if(i>=s.length()||s.charAt(i)!='"')throw new IllegalArgumentException("Expected key");String k=string();ws();expect(':');out.put(k,parse());ws();if(consume('}'))return out;expect(',');}while(true);}
        private Object array(){i++;List<Object> out=new ArrayList<>();ws();if(consume(']'))return out;do{out.add(parse());ws();if(consume(']'))return out;expect(',');}while(true);}
        private String string(){expect('"');StringBuilder b=new StringBuilder();while(i<s.length()){char c=s.charAt(i++);if(c=='"')return b.toString();if(c=='\\'){if(i>=s.length())throw new IllegalArgumentException("Invalid escape");char e=s.charAt(i++);switch(e){case '"'->b.append('"');case '\\'->b.append('\\');case '/'->b.append('/');case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())throw new IllegalArgumentException("Unicode escape");b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;}default->throw new IllegalArgumentException("Escape");}}else b.append(c);}throw new IllegalArgumentException("Unterminated string");}
        private Object literal(){if(s.startsWith("true",i)){i+=4;return true;}if(s.startsWith("false",i)){i+=5;return false;}if(s.startsWith("null",i)){i+=4;return null;}throw new IllegalArgumentException("Bad literal");}
        private Double number(){int st=i;if(i<s.length()&&s.charAt(i)=='-')i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;if(i<s.length()&&s.charAt(i)=='.'){i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}if(i<s.length()&&(s.charAt(i)=='e'||s.charAt(i)=='E')){i++;if(i<s.length()&&(s.charAt(i)=='+'||s.charAt(i)=='-'))i++;while(i<s.length()&&Character.isDigit(s.charAt(i)))i++;}if(st==i)throw new IllegalArgumentException("Bad JSON number");return Double.parseDouble(s.substring(st,i));}
        private boolean consume(char c){ws();if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        private void expect(char c){if(!consume(c))throw new IllegalArgumentException("Expected "+c);}
    }
}
