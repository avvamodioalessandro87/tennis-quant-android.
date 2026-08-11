package it.tennisquant.app;

import java.util.*;

public class FixedMainActivity extends MainActivity {
    Rating resolveLocalPlayer(String query){
        String q=norm(query);
        Rating exact=ratings.get(q);
        if(exact!=null)return exact;
        List<Rating> matches=new ArrayList<>();
        String[] qt=q.split(" ");
        String last=qt.length==0?q:qt[qt.length-1];
        for(Rating r:ratings.values()){
            String n=norm(r.name);
            String[] nt=n.split(" ");
            String nl=nt.length==0?n:nt[nt.length-1];
            if(n.equals(q)||n.endsWith(" "+q)||n.startsWith(q+" ")||nl.equals(last)||n.contains(q)) matches.add(r);
        }
        LinkedHashMap<String,Rating> unique=new LinkedHashMap<>();
        for(Rating r:matches) unique.put(norm(r.name),r);
        if(unique.size()==1)return unique.values().iterator().next();
        return null;
    }

    @Override void analyze(){
        String a=playerA.getText().toString().trim();
        String b=playerB.getText().toString().trim();
        if(a.isEmpty()||b.isEmpty()){result.setText("Inserisci i due giocatori.");return;}
        Rating ra=resolveLocalPlayer(a), rb=resolveLocalPlayer(b);
        if(ra==null||rb==null){
            StringBuilder msg=new StringBuilder("Impossibile identificare in modo univoco: ");
            if(ra==null)msg.append(a);
            if(ra==null&&rb==null)msg.append(" / ");
            if(rb==null)msg.append(b);
            msg.append(". Prova nome+cognome completo. Se il giocatore e' realmente nuovo, verra' aggiunto in una release con snapshot esteso.");
            result.setText(msg.toString());
            return;
        }
        playerA.setText(ra.name);
        playerB.setText(rb.name);
        super.analyze();
    }
}
