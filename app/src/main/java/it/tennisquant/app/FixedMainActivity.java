package it.tennisquant.app;

import org.json.*;
import java.net.URLEncoder;
import java.util.*;

public class FixedMainActivity extends MainActivity {
    final Map<String,Long> liveIds=new HashMap<>();

    static class ResolvedPlayer {
        String name; long id; Rating rating; boolean bootstrap; int historyMatches;
        ResolvedPlayer(String n,long i,Rating r,boolean b,int h){name=n;id=i;rating=r;bootstrap=b;historyMatches=h;}
    }

    Rating resolveLocalPlayer(String query){
        String q=norm(query);
        Rating exact=ratings.get(q);
        if(exact!=null)return exact;
        List<Rating> matches=new ArrayList<>();
        for(Rating r:ratings.values()){
            String n=norm(r.name);
            String[] nt=n.split(" ");
            String last=nt.length==0?n:nt[nt.length-1];
            if(n.equals(q)||n.endsWith(" "+q)||n.startsWith(q+" ")||last.equals(q)||n.contains(q)) matches.add(r);
        }
        LinkedHashMap<String,Rating> unique=new LinkedHashMap<>();
        for(Rating r:matches) unique.put(norm(r.name),r);
        return unique.size()==1?unique.values().iterator().next():null;
    }

    String canonicalFromSearch(String query,String key)throws Exception{
        Object root=getJson(BASE+"/tennis/v2/search?search="+URLEncoder.encode(query,"UTF-8"),key);
        if(!(root instanceof JSONObject))throw new Exception("Risposta ricerca non valida");
        JSONArray buckets=((JSONObject)root).optJSONArray("data");
        if(buckets==null)throw new Exception("Nessun risultato live");
        List<String> candidates=new ArrayList<>();
        String q=norm(query);
        for(int i=0;i<buckets.length();i++){
            JSONObject bucket=buckets.optJSONObject(i); if(bucket==null)continue;
            if(!"player_atp".equals(bucket.optString("category")))continue;
            JSONArray arr=bucket.optJSONArray("result"); if(arr==null)continue;
            for(int j=0;j<arr.length();j++){
                JSONObject p=arr.optJSONObject(j); if(p==null)continue;
                String name=p.optString("name","").trim(); if(name.isEmpty())continue;
                if(norm(name).equals(q))return name;
                candidates.add(name);
            }
        }
        LinkedHashMap<String,String> u=new LinkedHashMap<>();
        for(String n:candidates)u.put(norm(n),n);
        if(u.size()==1)return u.values().iterator().next();
        if(u.isEmpty())throw new Exception("Giocatore non trovato nel database live ATP");
        throw new Exception("Nome ambiguo: usa nome e cognome completi");
    }

    long findPlayerIdExact(String canonical,String key)throws Exception{
        String nk=norm(canonical); if(liveIds.containsKey(nk))return liveIds.get(nk);
        for(int page=1;page<=80;page++){
            Object root=getJson(BASE+"/tennis/v2/ms-api/atp/player?pageSize=100&pageNo="+page,key);
            JSONArray arr=dataArray(root);
            for(int i=0;i<arr.length();i++){
                JSONObject p=arr.optJSONObject(i); if(p==null)continue;
                String name=p.optString("name",""); long id=p.optLong("id",0);
                if(id>0&&!name.isEmpty())liveIds.put(norm(name),id);
                if(id>0&&norm(name).equals(nk))return id;
            }
            boolean next=(root instanceof JSONObject)&&((JSONObject)root).optBoolean("hasNextPage",false);
            if(!next)break;
        }
        throw new Exception("ID giocatore non disponibile nel catalogo ATP live");
    }

    String surfaceOf(JSONObject m){
        JSONObject t=m.optJSONObject("tournament");
        if(t!=null){
            JSONObject c=t.optJSONObject("court");
            String name=c==null?"":c.optString("name",c.optString("court",""));
            if(!name.isEmpty()){
                String x=name.toLowerCase(Locale.ROOT);
                if(x.contains("clay"))return "Clay";
                if(x.contains("grass"))return "Grass";
                return "Hard";
            }
            int id=t.optInt("courtId",0);
            if(id==2)return "Clay"; if(id==5)return "Grass";
        }
        return "Hard";
    }

    ResolvedPlayer resolvePlayer(String query,String key)throws Exception{
        Rating local=resolveLocalPlayer(query);
        String canonical=local!=null?local.name:canonicalFromSearch(query,key);
        long id=findPlayerIdExact(canonical,key);
        if(local!=null){
            Rating r=new Rating(local.name,local.elo,local.hard,local.clay,local.grass);
            updateFromRecent(r,id,key);
            return new ResolvedPlayer(canonical,id,r,false,0);
        }
        Rating r=new Rating(canonical,1500,1500,1500,1500);
        List<Ev> evs=new ArrayList<>();
        for(int page=1;page<=5;page++){
            String u=BASE+"/tennis/v2/ms-api/atp/player/past-matches/"+id+"?include=tournament.court&filter=GameYear:2024,2025,2026&pageSize=100&pageNo="+page;
            Object root=getJson(u,key); JSONArray arr=dataArray(root);
            for(int i=0;i<arr.length();i++){
                JSONObject m=arr.optJSONObject(i); if(m==null)continue;
                String date=m.optString("date",""); if(date.length()<10)continue;
                long p1id=m.optLong("player1Id",0),p2id=m.optLong("player2Id",0);
                JSONObject p1=m.optJSONObject("player1"),p2=m.optJSONObject("player2");
                boolean is1=p1id==id || (p1!=null&&norm(p1.optString("name","")).equals(norm(canonical)));
                boolean is2=p2id==id || (p2!=null&&norm(p2.optString("name","")).equals(norm(canonical)));
                if(!is1&&!is2)continue;
                int winner=parseWinner(m.optString("result","")); if(winner==0)continue;
                boolean won=(is1&&winner==1)||(is2&&winner==2);
                String opp=is1?(p2==null?"":p2.optString("name","")):(p1==null?"":p1.optString("name",""));
                evs.add(new Ev(date.substring(0,10),opp,surfaceOf(m),won));
            }
            boolean next=(root instanceof JSONObject)&&((JSONObject)root).optBoolean("hasNextPage",false);
            if(!next)break;
        }
        Collections.sort(evs,Comparator.comparing(x->x.date));
        for(Ev e:evs){
            Rating o=resolveLocalPlayer(e.opp); double oe=o==null?1500:o.elo, os=o==null?1500:o.surf(e.surf);
            double y=e.won?1:0,p=eloP(r.elo,oe); r.elo+=K*(y-p);
            double ps=eloP(r.surf(e.surf),os); r.setSurf(e.surf,r.surf(e.surf)+K*(y-ps));
        }
        return new ResolvedPlayer(canonical,id,r,true,evs.size());
    }

    @Override void analyze(){
        final String qa=playerA.getText().toString().trim(), qb=playerB.getText().toString().trim(), s=surface.getSelectedItem().toString(), key=apiKey.getText().toString().trim();
        if(qa.isEmpty()||qb.isEmpty()){result.setText("Inserisci i due giocatori.");return;}
        if(key.isEmpty()){result.setText("Per cercare tutti i professionisti ATP/Challenger/ITF serve la RapidAPI key gratuita salvata nell'app.");return;}
        result.setText("Ricerca giocatori e ricostruzione rating in corso… quote bookmaker escluse dal Prediction Engine.");
        new Thread(()->{
            try{
                ResolvedPlayer a=resolvePlayer(qa,key), b=resolvePlayer(qb,key);
                double ra=WG*a.rating.elo+WS*a.rating.surf(s), rb=WG*b.rating.elo+WS*b.rating.surf(s);
                double pa=eloP(ra,rb), pb=1-pa; pA=pa;pB=pb;lastA=a.name;lastB=b.name;
                String ca=a.bootstrap?(a.historyMatches>=30?"MEDIA":"BASSA"):"ALTA";
                String cb=b.bootstrap?(b.historyMatches>=30?"MEDIA":"BASSA"):"ALTA";
                String sa=statsText(a.id,key), sb=statsText(b.id,key);
                String txt=String.format(Locale.ITALY,
                    "MODELLO M1 + RESOLVER LIVE\n%s %.2f%% · quota equa %.3f\n%s %.2f%% · quota equa %.3f\n\nElo %.1f / %.1f\nSurface Elo %.1f / %.1f\n\nQualità rating: %s %s · %s %s\nStorico bootstrap: %d / %d match\n\nIndicatori live non ancora pesati:\n%s: %s\n%s: %s",
                    a.name,100*pa,1/pa,b.name,100*pb,1/pb,a.rating.elo,b.rating.elo,a.rating.surf(s),b.rating.surf(s),a.name,ca,b.name,cb,a.historyMatches,b.historyMatches,a.name,sa,b.name,sb);
                runOnUiThread(()->{playerA.setText(a.name);playerB.setText(b.name);result.setText(txt);});
            }catch(Exception e){runOnUiThread(()->result.setText("Analisi non disponibile: "+e.getMessage()+"\nNessun dato viene inventato."));}
        }).start();
    }
}
