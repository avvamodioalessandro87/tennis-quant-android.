package it.tennisquant.app;

import android.app.Activity;
import android.os.Bundle;
import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MainActivity extends Activity {
    static final String HOST="tennis-api-atp-wta-itf.p.rapidapi.com";
    static final String BASE="https://"+HOST;
    static final String CUTOFF="2026-05-25";
    static final double K=32.0, WG=0.80, WS=0.20;
    EditText apiKey, playerA, playerB, oddsA, oddsB, bankroll;
    TextView result, marketResult, ledger, status;
    Spinner surface;
    final Map<String,Rating> ratings=new HashMap<>();
    double pA=Double.NaN,pB=Double.NaN,stakeA=0,stakeB=0,lastOddsA=0,lastOddsB=0;
    String lastA="",lastB="";
    BetDb db;

    static class Rating { String name; double elo,hard,clay,grass; Rating(String n,double e,double h,double c,double g){name=n;elo=e;hard=h;clay=c;grass=g;} double surf(String s){return s.equals("Clay")?clay:s.equals("Grass")?grass:hard;} void setSurf(String s,double v){if(s.equals("Clay"))clay=v;else if(s.equals("Grass"))grass=v;else hard=v;} }
    static class Ev { String date,opp,surf; boolean won; Ev(String d,String o,String s,boolean w){date=d;opp=o;surf=s;won=w;} }

    @Override public void onCreate(Bundle b){
        super.onCreate(b); setContentView(R.layout.activity_main);
        apiKey=findViewById(R.id.apiKey); playerA=findViewById(R.id.playerA); playerB=findViewById(R.id.playerB); oddsA=findViewById(R.id.oddsA); oddsB=findViewById(R.id.oddsB); bankroll=findViewById(R.id.bankroll);
        result=findViewById(R.id.result); marketResult=findViewById(R.id.marketResult); ledger=findViewById(R.id.ledger); status=findViewById(R.id.status); surface=findViewById(R.id.surface);
        surface.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Hard","Clay","Grass"}));
        db=new BetDb(this); loadRatings();
        String saved=getSharedPreferences("tq",MODE_PRIVATE).getString("rapid_key",""); apiKey.setText(saved);
        findViewById(R.id.saveKey).setOnClickListener(v->{getSharedPreferences("tq",MODE_PRIVATE).edit().putString("rapid_key",apiKey.getText().toString().trim()).apply(); Toast.makeText(this,"Chiave salvata solo sul dispositivo",Toast.LENGTH_SHORT).show();});
        findViewById(R.id.analyze).setOnClickListener(v->analyze()); findViewById(R.id.market).setOnClickListener(v->market());
        findViewById(R.id.betA).setOnClickListener(v->recordBet("A")); findViewById(R.id.betB).setOnClickListener(v->recordBet("B"));
        findViewById(R.id.won).setOnClickListener(v->settle(true)); findViewById(R.id.lost).setOnClickListener(v->settle(false)); findViewById(R.id.stats).setOnClickListener(v->showStats());
        showStats();
    }

    String norm(String s){return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+"," ");}
    void loadRatings(){
        try(BufferedReader br=new BufferedReader(new InputStreamReader(getAssets().open("atp_ratings_20260525.csv"),StandardCharsets.UTF_8))){
            String line=br.readLine(); while((line=br.readLine())!=null){String[] x=line.split(","); if(x.length<5)continue; Rating r=new Rating(x[0],Double.parseDouble(x[1]),Double.parseDouble(x[2]),Double.parseDouble(x[3]),Double.parseDouble(x[4])); ratings.put(norm(r.name),r);}
            status.setText("ATP · snapshot 25/05/2026 + aggiornamento live gratuito");
        }catch(Exception e){status.setText("Errore snapshot: "+e.getMessage());}
    }

    void analyze(){
        final String a=playerA.getText().toString().trim(), b=playerB.getText().toString().trim(), s=surface.getSelectedItem().toString(), key=apiKey.getText().toString().trim();
        if(a.isEmpty()||b.isEmpty()){result.setText("Inserisci i due giocatori.");return;}
        Rating ra=ratings.get(norm(a)), rb=ratings.get(norm(b)); if(ra==null||rb==null){result.setText("Giocatore non presente nello snapshot ATP. Verifica il nome esatto.");return;}
        result.setText("Calcolo in corso… quote bookmaker escluse dal Prediction Engine.");
        new Thread(()->{
            try{
                Rating ca=new Rating(ra.name,ra.elo,ra.hard,ra.clay,ra.grass), cb=new Rating(rb.name,rb.elo,rb.hard,rb.clay,rb.grass);
                String extra="";
                if(!key.isEmpty()){
                    long ida=searchPlayerId(a,key), idb=searchPlayerId(b,key);
                    if(ida>0) updateFromRecent(ca,ida,key); if(idb>0) updateFromRecent(cb,idb,key);
                    String sa=ida>0?statsText(ida,key):""; String sb=idb>0?statsText(idb,key):"";
                    extra="\n\nIndicatori live (non ancora pesati nel modello):\n"+a+": "+sa+"\n"+b+": "+sb;
                } else extra="\n\nNessuna RapidAPI key: uso snapshot del 25/05/2026, quindi NON dati live.";
                double rca=WG*ca.elo+WS*ca.surf(s), rcb=WG*cb.elo+WS*cb.surf(s); double pa=eloP(rca,rcb), pb=1-pa;
                pA=pa;pB=pb;lastA=a;lastB=b;
                String txt=String.format(Locale.ITALY,"MODELLO ATTIVO M1 VALIDATO\n%s %.2f%% · quota equa %.3f\n%s %.2f%% · quota equa %.3f\n\nElo aggiornato: %.1f / %.1f\nSurface Elo: %.1f / %.1f%s",a,100*pa,1/pa,b,100*pb,1/pb,ca.elo,cb.elo,ca.surf(s),cb.surf(s),extra);
                runOnUiThread(()->result.setText(txt));
            }catch(Exception e){runOnUiThread(()->result.setText("Errore dati live: "+e.getMessage()+"\nIl motore non genera numeri sostitutivi."));}
        }).start();
    }

    double eloP(double a,double b){return 1.0/(1.0+Math.pow(10.0,(b-a)/400.0));}
    void updateFromRecent(Rating me,long playerId,String key)throws Exception{
        String u=BASE+"/tennis/v2/ms-api/atp/player/past-matches/"+playerId+"?include=tournament.court&filter=GameYear:2026&pageSize=100&pageNo=1";
        Object root=getJson(u,key); JSONArray arr=dataArray(root); List<Ev> evs=new ArrayList<>();
        for(int i=0;i<arr.length();i++){JSONObject m=arr.optJSONObject(i);if(m==null)continue;String date=m.optString("date","");if(date.length()<10||date.substring(0,10).compareTo(CUTOFF)<=0)continue;
            JSONObject p1=m.optJSONObject("player1"),p2=m.optJSONObject("player2");if(p1==null||p2==null)continue;String n1=p1.optString("name"),n2=p2.optString("name");boolean is1=norm(n1).equals(norm(me.name)); if(!is1&&!norm(n2).equals(norm(me.name)))continue;
            int winner=parseWinner(m.optString("result",""));if(winner==0)continue;boolean won=(is1&&winner==1)||(!is1&&winner==2);String opp=is1?n2:n1;String sf="Hard";JSONObject t=m.optJSONObject("tournament");if(t!=null){JSONObject c=t.optJSONObject("court");if(c!=null)sf=c.optString("name","Hard");}
            if(sf.toLowerCase(Locale.ROOT).contains("clay"))sf="Clay";else if(sf.toLowerCase(Locale.ROOT).contains("grass"))sf="Grass";else sf="Hard";evs.add(new Ev(date.substring(0,10),opp,sf,won));}
        Collections.sort(evs,Comparator.comparing(x->x.date));
        for(Ev e:evs){Rating o=ratings.get(norm(e.opp));double oe=o==null?1500:o.elo, os=o==null?1500:o.surf(e.surf);double y=e.won?1:0,p=eloP(me.elo,oe);me.elo+=K*(y-p);double ps=eloP(me.surf(e.surf),os);me.setSurf(e.surf,me.surf(e.surf)+K*(y-ps));}
    }

    int parseWinner(String r){if(r==null)return 0;int a=0,b=0;for(String set:r.split(" ")){String clean=set.replaceAll("\\(.*?\\)","");String[] z=clean.split("-");if(z.length<2)continue;try{int x=Integer.parseInt(z[0].replaceAll("\\D","")),y=Integer.parseInt(z[1].replaceAll("\\D",""));if(x>y)a++;else if(y>x)b++;}catch(Exception ignored){}}return a>b?1:b>a?2:0;}

    String statsText(long id,String key){try{Object root=getJson(BASE+"/tennis/v2/ms-api/atp/player/match-stats/"+id,key);JSONObject d=dataObject(root);JSONObject sv=d.optJSONObject("serviceStats"),rt=d.optJSONObject("rtnStats");if(sv==null)return "stats n/d";double f1=ratio(sv,"winningOnFirstServeGm","winningOnFirstServeOfGm"),f2=ratio(sv,"winningOnSecondServeGm","winningOnSecondServeOfGm"),r1=rt==null?0:ratio(rt,"winningOnFirstServeGm","winningOnFirstServeOfGm");return String.format(Locale.ITALY,"1ª %.1f%% · 2ª %.1f%% · return1 %.1f%%",100*f1,100*f2,100*r1);}catch(Exception e){return "stats n/d";}}
    double ratio(JSONObject o,String a,String b){double d=o.optDouble(b,0);return d==0?0:o.optDouble(a,0)/d;}

    long searchPlayerId(String name,String key)throws Exception{Object root=getJson(BASE+"/tennis/v2/search?search="+URLEncoder.encode(name,"UTF-8"),key);return findId(root,name);}
    long findId(Object o,String target){try{if(o instanceof JSONObject){JSONObject j=(JSONObject)o;String n=j.optString("name",j.optString("player_name",""));if(!n.isEmpty()&&norm(n).equals(norm(target))){long id=j.optLong("id",j.optLong("player_id",0));if(id>0)return id;}Iterator<String> it=j.keys();while(it.hasNext()){long z=findId(j.opt(it.next()),target);if(z>0)return z;}}else if(o instanceof JSONArray){JSONArray a=(JSONArray)o;for(int i=0;i<a.length();i++){long z=findId(a.opt(i),target);if(z>0)return z;}}}catch(Exception ignored){}return 0;}

    Object getJson(String url,String key)throws Exception{HttpURLConnection c=(HttpURLConnection)new URL(url).openConnection();c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setRequestProperty("X-RapidAPI-Key",key);c.setRequestProperty("X-RapidAPI-Host",HOST);int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();String txt=read(in);if(code>=400)throw new IOException("HTTP "+code+": "+txt);String t=txt.trim();return t.startsWith("[")?new JSONArray(t):new JSONObject(t);}
    String read(InputStream in)throws Exception{BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();String l;while((l=b.readLine())!=null)s.append(l);return s.toString();}
    JSONArray dataArray(Object r){if(r instanceof JSONArray)return (JSONArray)r;JSONObject j=(JSONObject)r;JSONArray a=j.optJSONArray("data");if(a==null)a=j.optJSONArray("result");return a==null?new JSONArray():a;}
    JSONObject dataObject(Object r){if(!(r instanceof JSONObject))return new JSONObject();JSONObject j=(JSONObject)r;JSONObject d=j.optJSONObject("data");return d==null?j:d;}

    void market(){if(Double.isNaN(pA)){marketResult.setText("Prima genera e congela una prediction.");return;}try{lastOddsA=oddsA.getText().length()>0?Double.parseDouble(oddsA.getText().toString()):0;lastOddsB=oddsB.getText().length()>0?Double.parseDouble(oddsB.getText().toString()):0;double bank=Double.parseDouble(bankroll.getText().toString());StringBuilder s=new StringBuilder();if(lastOddsA>1){double ev=pA*lastOddsA-1;stakeA=stake(bank,pA,lastOddsA);s.append(String.format(Locale.ITALY,"A: EV %.2f%% · stake suggerito €%.2f\n",100*ev,stakeA));}if(lastOddsB>1){double ev=pB*lastOddsB-1;stakeB=stake(bank,pB,lastOddsB);s.append(String.format(Locale.ITALY,"B: EV %.2f%% · stake suggerito €%.2f",100*ev,stakeB));}marketResult.setText(s.toString());}catch(Exception e){marketResult.setText("Quote/bankroll non validi.");}}
    double stake(double bank,double p,double odds){if(odds<=1)return 0;double b=odds-1,q=1-p,kelly=(b*p-q)/b;double frac=Math.max(0,kelly*.25);frac=Math.min(.02,frac);return Math.round(bank*frac*100.0)/100.0;}

    void recordBet(String sel){if(Double.isNaN(pA)){Toast.makeText(this,"Manca prediction",Toast.LENGTH_SHORT).show();return;}double o=sel.equals("A")?lastOddsA:lastOddsB,st=sel.equals("A")?stakeA:stakeB;if(o<=1||st<=0){Toast.makeText(this,"Valuta prima il mercato: stake zero/non valido",Toast.LENGTH_SHORT).show();return;}SQLiteDatabase d=db.getWritableDatabase();ContentValues v=new ContentValues();v.put("pa",lastA);v.put("pb",lastB);v.put("sel",sel);v.put("odds",o);v.put("stake",st);v.put("status","OPEN");v.put("pnl",0);d.insert("bets",null,v);Toast.makeText(this,"Giocata reale registrata",Toast.LENGTH_SHORT).show();showStats();}
    void settle(boolean won){SQLiteDatabase d=db.getWritableDatabase();Cursor c=d.rawQuery("SELECT id,odds,stake FROM bets WHERE status='OPEN' ORDER BY id DESC LIMIT 1",null);if(!c.moveToFirst()){c.close();Toast.makeText(this,"Nessuna giocata aperta",Toast.LENGTH_SHORT).show();return;}long id=c.getLong(0);double o=c.getDouble(1),st=c.getDouble(2);c.close();ContentValues v=new ContentValues();v.put("status",won?"WON":"LOST");v.put("pnl",won?st*(o-1):-st);d.update("bets",v,"id=?",new String[]{String.valueOf(id)});showStats();}
    void showStats(){SQLiteDatabase d=db.getReadableDatabase();Cursor c=d.rawQuery("SELECT status,stake,pnl FROM bets",null);int settled=0,wins=0,open=0;double staked=0,pnl=0;while(c.moveToNext()){String s=c.getString(0);if(s.equals("OPEN")){open++;continue;}settled++;if(s.equals("WON"))wins++;staked+=c.getDouble(1);pnl+=c.getDouble(2);}c.close();double roi=staked==0?0:100*pnl/staked,wr=settled==0?0:100.0*wins/settled;ledger.setText(String.format(Locale.ITALY,"Giocate chiuse %d · aperte %d\nP/L €%.2f · ROI %.2f%% · Win rate %.2f%%",settled,open,pnl,roi,wr));}

    static class BetDb extends SQLiteOpenHelper {BetDb(Context c){super(c,"tennis_quant.db",null,1);}public void onCreate(SQLiteDatabase d){d.execSQL("CREATE TABLE bets(id INTEGER PRIMARY KEY AUTOINCREMENT,ts DATETIME DEFAULT CURRENT_TIMESTAMP,pa TEXT,pb TEXT,sel TEXT,odds REAL,stake REAL,status TEXT,pnl REAL)");}public void onUpgrade(SQLiteDatabase d,int o,int n){} }
}
