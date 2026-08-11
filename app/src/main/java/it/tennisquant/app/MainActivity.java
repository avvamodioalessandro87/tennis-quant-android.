package it.tennisquant.app;
import android.app.Activity; import android.os.Bundle; import android.widget.*;
public class MainActivity extends Activity {
 EditText playerA,playerB,oddsA,oddsB; TextView result,marketResult;
 @Override public void onCreate(Bundle b){super.onCreate(b);setContentView(R.layout.activity_main);playerA=findViewById(R.id.playerA);playerB=findViewById(R.id.playerB);oddsA=findViewById(R.id.oddsA);oddsB=findViewById(R.id.oddsB);result=findViewById(R.id.result);marketResult=findViewById(R.id.marketResult);Spinner s=findViewById(R.id.surface);s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"Hard","Clay","Grass"}));findViewById(R.id.analyze).setOnClickListener(v->analyze());findViewById(R.id.market).setOnClickListener(v->market());}
 private void analyze(){String a=playerA.getText().toString().trim(),b=playerB.getText().toString().trim();if(a.isEmpty()||b.isEmpty()){result.setText("Inserisci i due giocatori.");return;}result.setText("Match acquisito. Il Prediction Engine non legge le quote bookmaker. Dati live e coefficienti devono essere validati prima di produrre una probabilità reale.");}
 private void market(){marketResult.setText("Il Market Engine si attiva solo dopo una prediction valida e congelata.");}
}
