package data.campaign.intel.ui;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCustomUIPanelPlugin;
import com.fs.starfarer.api.input.InputEventAPI;
import com.fs.starfarer.api.ui.PositionAPI;
import com.fs.starfarer.api.ui.UIComponentAPI;
import data.hullmods.shipboardai.Moci_ModelCore;
import org.lwjgl.opengl.GL11;

import java.util.Arrays;
import java.util.List;

final class Moci_UIModelPortrait extends BaseCustomUIPanelPlugin {
    private static final double TURN = 2 * Math.PI;
    private static final int LIGHT = 0xf4ffff, SHADE = 0x080f15;
    private static final double[] WIDTHS = {-3.2, -.95, -.32, .32, .95, 3.2};
    private static final double[] GAINS = {0, .13, .97, .97, .13, 0};
    private static final Path RIBBON = Path.curves(0,90, 9,84,23,74,28,81, 35,92,10,98,0,90,
            -9,83,-23,74,-28,81, -35,92,-10,98,0,90, 24,73,86,103,109,59, 134,12,111,-70,62,-96,
            23,-118,-23,-118,-62,-96, -111,-70,-134,12,-109,59, -86,103,-24,108,0,90);
    private static final double LOBE = RIBBON.fraction(80), KNOT = RIBBON.fraction(160);
    private static final Path BRIDGE = Path.curves(-6,94, -3.5,93,2,89,6.2,86);
    private static final Path BOLT = new Path(132,14,102,54,120,49,92,93);
    private static final Path RETURN = Path.curves(92,93, 88,101,111,106,126,84, 140,63,143,39,139,23, 136,14,135,10,132,14);
    private static final double[] FOLDS = {-.52, -.05, .43};
    private static final long EPOCH = System.nanoTime();
    private final Moci_ModelCore model;
    private final UIComponentAPI portrait, lite;
    private final boolean finalForm, full;
    private final double period, entry;
    private final int primary, second;
    private final Point sample = new Point();
    private final Stroke stroke = new Stroke();
    private final long opened = System.nanoTime();
    private long pausedAt, pausedTime;
    private double opacity;
    private boolean revealed;

    static boolean reducedMotion() {
        return Global.getSettings().getModManager().isModEnabled("lunalib") && Boolean.TRUE.equals(
                lunalib.lunaSettings.LunaSettings.getBoolean("moci_shippack", "Moci_reduceModelPortraitMotion"));
    }

    Moci_UIModelPortrait(Moci_ModelCore model, UIComponentAPI portrait, UIComponentAPI lite, boolean finalForm, boolean full, boolean reduced) {
        this.model = model; this.portrait = portrait; this.lite = lite; this.finalForm = finalForm; this.full = full;
        switch (model) {
            case CHATGPT: period=8.8; entry=.94; primary=0xc9a4ff; second=0x93f8f0; break;
            case CLAUDE: period=8.2; entry=.97; primary=0xfbb36b; second=0xffedb9; break;
            case DEEPSEEK: period=6.9; entry=.31; primary=0x599fdc; second=0xa7f5f0; break;
            case GEMINI: period=26.4; entry=.11; primary=0xddb6ff; second=0xffdc93; break;
            case GROK: period=5.8; entry=.14; primary=0xff8f63; second=0xffe4a7; break;
            default: throw new IllegalArgumentException(model.name());
        }
        if (reduced) {
            double stillPhase = model == Moci_ModelCore.CHATGPT ? .14 : model == Moci_ModelCore.CLAUDE ? .18
                    : model == Moci_ModelCore.GROK ? .45 : model == Moci_ModelCore.GEMINI ? .11 : .50;
            pausedAt = opened;
            pausedTime = opened-EPOCH - (long) ((stillPhase-entry)*period*1e9);
        }
        if (lite != null) portrait.setOpacity(0f);
    }

    @Override public void advance(float amount) {
        if (lite == null || revealed) return;
        double fade = ease(0,.7,(System.nanoTime()-opened)*1e-9);
        portrait.setOpacity((float) fade);
        lite.setOpacity((float) (1-fade));
        revealed = fade >= 1;
    }

    @Override public void processInput(List<InputEventAPI> events) {
        if (!finalForm || (lite != null && !revealed)) return;
        for (InputEventAPI event : events) {
            if (event.isConsumed() || !event.isMouseDownEvent() || event.getEventValue() != 0
                    || !portrait.getPosition().containsEvent(event)) continue;
            long now = System.nanoTime();
            if (pausedAt == 0) pausedAt = now;
            else { pausedTime += now-pausedAt; pausedAt = 0; }
            event.consume();
        }
    }

    @Override public void render(float alphaMult) {
        if (!finalForm || (lite != null && !revealed) || !(alphaMult > 0)) return;
        PositionAPI image = portrait.getPosition();
        float scale = Math.min(image.getWidth(),image.getHeight()) / 200f;
        if (!(scale > 0)) return;
        long now = pausedAt == 0 ? System.nanoTime() : pausedAt;
        double phase = wrap(entry + (now-EPOCH-pausedTime)*1e-9/period);
        GL11.glPushAttrib(GL11.GL_ENABLE_BIT | GL11.GL_COLOR_BUFFER_BIT | GL11.GL_CURRENT_BIT | GL11.GL_LIGHTING_BIT);
        GL11.glPushMatrix();
        try {
            GL11.glDisable(GL11.GL_TEXTURE_2D); GL11.glDisable(GL11.GL_CULL_FACE); GL11.glDisable(GL11.GL_ALPHA_TEST);
            GL11.glEnable(GL11.GL_BLEND); GL11.glBlendFunc(GL11.GL_SRC_ALPHA,GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL11.glShadeModel(GL11.GL_SMOOTH);
            GL11.glTranslatef(image.getCenterX(),image.getCenterY(),0); GL11.glScalef(scale,-scale,1);
            draw(phase,alphaMult);
        } finally { GL11.glPopMatrix(); GL11.glPopAttrib(); }
    }

    void draw(double phase, double alpha) {
        opacity = alpha;
        switch (model) {
            case CHATGPT: chord(phase); break;
            case CLAUDE: flower(phase); break;
            case DEEPSEEK: tide(phase); break;
            case GEMINI: twins(phase); break;
            case GROK: lightning(phase); break;
        }
    }

    private static double wrap(double value) { return value-Math.floor(value); }
    private static double clamp(double value) { return Math.max(0,Math.min(1,value)); }
    private static double ease(double start,double end,double value) {
        double part=clamp((value-start)/(end-start)); return part*part*(3-2*part);
    }
    private static double tail(double part) { return ease(0,.92,part)*(.72+.28*part); }
    private static double edge(double part) { return ease(0,.14,part)*(1-ease(.84,1,part)); }
    private static double cubic(double start,double first,double second,double end,double part) {
        double inv=1-part; return inv*inv*(inv*start+3*part*first)+part*part*(3*inv*second+part*end);
    }

    private static final class Point { double across,down; }

    private static final class Path {
        final double[] coords, lengths;
        Path(double... coords) {
            this.coords=coords; lengths=new double[coords.length/2];
            for (int index=1;index<lengths.length;index++) lengths[index]=lengths[index-1]+Math.hypot(
                    coords[2*index]-coords[2*index-2],coords[2*index+1]-coords[2*index-1]);
        }
        double fraction(int index) { return lengths[index]/lengths[lengths.length-1]; }
        static Path curves(double startX,double startY,double... legs) {
            double[] coords=new double[2+legs.length/6*80]; int cursor=2; coords[0]=startX; coords[1]=startY;
            for (int leg=0;leg<legs.length;leg+=6) {
                for (int step=1;step<=40;step++) {
                    double part=step/40.;
                    coords[cursor++]=cubic(startX,legs[leg],legs[leg+2],legs[leg+4],part);
                    coords[cursor++]=cubic(startY,legs[leg+1],legs[leg+3],legs[leg+5],part);
                }
                startX=legs[leg+4]; startY=legs[leg+5];
            }
            return new Path(coords);
        }
        void at(double part,Point point,int side,double offset) {
            double distance=clamp(part)*lengths[lengths.length-1]; int upper=Arrays.binarySearch(lengths,distance);
            upper=Math.min(lengths.length-1,Math.max(1,upper<0 ? -upper-1 : upper));
            int base=2*upper-2; double segment=lengths[upper]-lengths[upper-1];
            double along=(distance-lengths[upper-1])/segment;
            double dx=coords[base+2]-coords[base],dy=coords[base+3]-coords[base+1];
            point.across=side*(coords[base]+along*dx+offset*dy/segment);
            point.down=coords[base+1]+along*dy-offset*dx/segment;
        }
    }

    private void color(int rgb,double alpha) {
        GL11.glColor4f(((rgb>>16)&255)/255f,((rgb>>8)&255)/255f,(rgb&255)/255f,(float)(opacity*alpha));
    }
    private final class Stroke {
        final double[] horizontal=new double[100], vertical=new double[100], strength=new double[100], normalX=new double[100], normalY=new double[100];
        int count;
        void add(double across,double down,double gain) {
            horizontal[count]=across; vertical[count]=down; strength[count++]=gain;
        }
        void paint(int ink,double width,double alpha) {
            if (count>=2 && alpha>.003) {
                boolean closed=count>3 && Math.abs(horizontal[0]-horizontal[count-1])<1e-5
                        && Math.abs(vertical[0]-vertical[count-1])<1e-5 && Math.abs(strength[0]-strength[count-1])<1e-5;
                for (int index=0;index<count;index++) {
                    int prev=closed && index==0?count-2:Math.max(0,index-1);
                    int next=closed && index==count-1?1:Math.min(count-1,index+1);
                    double dx=horizontal[next]-horizontal[prev],dy=vertical[next]-vertical[prev];
                    double half=.55*width*(.20+.80*Math.sqrt(strength[index]))/Math.max(.001,Math.hypot(dx,dy));
                    normalX[index]=-dy*half; normalY[index]=dx*half;
                }
                for (int band=0;band<WIDTHS.length-1;band++) {
                    GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
                    for (int index=0;index<count;index++) {
                        for (int bound=band;bound<=band+1;bound++) {
                            color(ink,alpha*GAINS[bound]*strength[index]);
                            GL11.glVertex2d(horizontal[index]+WIDTHS[bound]*normalX[index],vertical[index]+WIDTHS[bound]*normalY[index]);
                        }
                    }
                    GL11.glEnd();
                }
            }
            count=0;
        }
    }

    private void disc(double across,double down,double radius,int ink,double alpha) {
        GL11.glBegin(GL11.GL_TRIANGLE_FAN); color(ink,alpha); GL11.glVertex2d(across,down); color(ink,0);
        for (int step=0;step<=18;step++) GL11.glVertex2d(across+radius*Math.cos(step*TURN/18),down+radius*Math.sin(step*TURN/18));
        GL11.glEnd();
    }
    private void pearl(double across,double down,int ink,double size) {
        disc(across,down,6.6*size,ink,.30); disc(across,down,3.5*size,ink,.71); disc(across,down,1.45*size,LIGHT,1);
    }
    private void star(double across,double down,double radius,double angle,int ink,boolean hollow) {
        disc(across,down,radius*1.9,ink,.25);
        if (!hollow) { GL11.glBegin(GL11.GL_TRIANGLE_FAN); color(ink,1); GL11.glVertex2d(across,down); }
        int points=hollow?4:8;
        for (int index=0;index<=points;index++) {
            double turn=TURN*index/points-Math.PI/2, distance=radius*(hollow||index%2==0?1:.32);
            double dx=distance*Math.cos(turn)*(hollow?.72:1),dy=distance*Math.sin(turn);
            double posX=across+dx*Math.cos(angle)-dy*Math.sin(angle),posY=down+dx*Math.sin(angle)+dy*Math.cos(angle);
            if (hollow) stroke.add(posX,posY,1); else GL11.glVertex2d(posX,posY);
        }
        if (hollow) stroke.paint(ink,1.5,1); else GL11.glEnd();
    }

    private void chord(double phase) {
        double span=full?.215:.14;
        for(int side=1;side>=-1;side-=2) {
            int ink=side==1?primary:second;
            for(int layer=0;layer<(full?2:1);layer++) {
                for(int index=0;index<=88;index++) {
                    double part=index/88.; RIBBON.at(wrap(phase-layer*.008-span*(layer==0?1:.71)*(1-part)),sample,side,layer*2.65);
                    stroke.add(sample.across,sample.down,tail(part));
                }
                stroke.paint(ink,layer==1?.75:full?2.15:1.55,layer==1?.65:.98);
            }
            double etched=1-ease(KNOT+.008,KNOT+.13,phase);
            if (etched>.01 && phase>0) {
                for (int index=0;index<=34;index++) {
                    double part=index/34.; RIBBON.at(Math.min(LOBE,phase)*part,sample,side,0);
                    stroke.add(sample.across,sample.down,.22+.68*Math.sin(Math.PI*part));
                }
                stroke.paint(ink,full?1.65:1,etched*(full?.88:.57));
            }
            RIBBON.at(phase,sample,side,0); pearl(sample.across,sample.down,ink,full?1.08:.85);
        }
        double woven=full?ease(LOBE*.5,LOBE*1.3,phase)*(1-ease(KNOT+.016,KNOT+.1,phase)):0;
        if (woven>.008) {
            for(int layer=0;layer<=1;layer++) {
                for(int index=0;index<=16;index++) { double part=index/16.; BRIDGE.at(part,sample,1,0); stroke.add(sample.across,sample.down,edge(part)); }
                stroke.paint(layer==0?SHADE:second,layer==0?3.05:1.35,woven);
            }
            disc(0,90,6.5,LIGHT,woven*.17);
        }
    }

    private void flower(double phase) {
        for(int leaf=0;leaf<(full?3:1);leaf++) {
            double age=phase-(full?leaf*.027:0),open=ease(.003,.143,age),fade=1-ease(.155,.59,age);
            if(open*fade<.003) continue;
            GL11.glPushMatrix();
            GL11.glTranslated(45+2.3*(1-fade),-62-2.8*(1-fade),0);
            GL11.glRotated(Math.toDegrees(FOLDS[full?leaf:1]-.23*(1-open)),0,0,1); GL11.glScaled(.15+.85*open,.22+.78*open,1);
            GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
            for(int index=0;index<=24;index++) {
                double part=index/24.; color(part<.5?primary:second,open*fade*(.09+.37*Math.sin(part*Math.PI)));
                GL11.glVertex2d(cubic(0,6,25,39,part),cubic(0,-14,-30,-30,part));
                GL11.glVertex2d(cubic(0,20,34,39,part),cubic(0,-4,-17,-30,part));
            }
            GL11.glEnd();
            for (int rib=0;rib<2;rib++) {
                for(int index=0;index<=24;index++) {
                    double part=index/24.; stroke.add(cubic(0,rib==0?6:9,rib==0?25:23,rib==0?39:38,part),
                            cubic(0,rib==0?-14:-7,rib==0?-30:-20,rib==0?-30:-29,part),edge(part));
                }
                stroke.paint(rib==0?primary:second,rib==0?1.28:.76,open*fade*.9);
            }
            GL11.glPopMatrix();
        }
        for(int index=0;index<=56;index++) {
            double part=index/56.,angle=TURN*(phase-(full?.157:.09)*(1-part))-.76;
            stroke.add(112*Math.cos(angle),104*Math.sin(angle),tail(part));
        }
        stroke.paint(primary,full?1.65:1.2,.88);
        double angle=TURN*phase-.76; star(112*Math.cos(angle),104*Math.sin(angle),full?6.6:5.1,0,second,false);
    }

    private static double water(double across,double phase,boolean back) {
        return 97+(back?6:0)+3.6*Math.sin(across*.035-TURN*phase+(back?1.4:0))+1.35*Math.sin(across*.091-2*TURN*phase+.17);
    }
    private void tide(double phase) {
        double lift=ease(.19,.44,phase)*(1-ease(.53,.82,phase));
        if(full && lift>.005) {
            double center=22+10*Math.sin(TURN*(phase-.34)),surface=water(center,phase,false);
            double angle=-.16+.09*Math.sin(TURN*phase),cos=Math.cos(angle),sin=Math.sin(angle);
            for (int side=-1;side<=1;side+=2) {
                GL11.glBegin(GL11.GL_TRIANGLE_STRIP);
                for (int index=0;index<=28;index++) {
                    double part=index/28.;
                    for(int rim=0;rim<=1;rim++) {
                        double dx=side*cubic(rim==0?0:2,rim==0?4:4,rim==0?12:17,side<0?22:20,part);
                        double dy=cubic(rim==0?-12:3,rim==0?-19:-8,rim==0?-21:-9,side<0?-22:-20,part);
                        double across=center+dx*cos-dy*sin, down=surface+25*(1-lift)+dx*sin+dy*cos;
                        double waterline=water(across,phase,false);
                        color(rim==0?second:primary,(rim==0?.94:.78)*ease(0,2,waterline-down));
                        GL11.glVertex2d(across,Math.min(waterline,down));
                    }
                }
                GL11.glEnd();
            }
        }
        for(int layer=0;layer<(full?2:1);layer++) {
            for(int index=0;index<=80;index++) { double part=index/80.,across=-116+232*part; stroke.add(across,water(across,phase,layer!=0),edge(part)); }
            stroke.paint(layer==0?primary:second,layer==0?1.65:1.2,layer==0?.98:.86);
        }
    }

    private void twins(double phase) {
        for(int orbit=0;orbit<2;orbit++) {
            int ink=orbit==0?primary:second,turns=orbit==0?4:-3; double radius=orbit==0?100:116,center=orbit==0?-4:4;
            double offset=orbit==0?-.64:2.56,angle=TURN*turns*phase+offset;
            for(int index=0;index<=88;index++) {
                double arc=TURN*index/88.; stroke.add(center+radius*Math.cos(arc),center/4+radius*Math.sin(arc),.5+.39*Math.cos(arc-.83));
            }
            stroke.paint(ink,.8,full?.25:.15);
            for(int index=0;index<=54;index++) {
                double part=index/54.,arc=angle-Math.signum(turns)*(full?1.36:.66)*(1-part);
                stroke.add(center+radius*Math.cos(arc),center/4+radius*Math.sin(arc),tail(part));
            }
            stroke.paint(ink,full?1.9:1.15,.95);
            star(center+radius*Math.cos(angle),center/4+radius*Math.sin(angle),orbit==0?6.4:6,(angle-offset)*.5,ink,orbit!=0);
        }
    }

    private void ember(double phase) {
        double part=wrap(phase);
        if(part<.43) BOLT.at(ease(0,.43,part),sample,1,0); else RETURN.at((part-.43)/.57,sample,1,0);
    }
    private void lightning(double phase) {
        double written=ease(0,.43,phase),remaining=1-ease(.49,.87,phase);
        if(written>.001 && remaining>.002) {
            double stamp=full?ease(.31,.445,phase)*remaining:0;
            if(stamp>.004) {
                GL11.glBegin(GL11.GL_TRIANGLE_STRIP); color(second,.70*stamp); GL11.glVertex2d(132,14);
                color(primary,.57*stamp); GL11.glVertex2d(102,54); GL11.glVertex2d(113,48);
                GL11.glVertex2d(120,49); GL11.glVertex2d(130,43); color(primary,.18*stamp); GL11.glVertex2d(92,93); GL11.glEnd();
            }
            for(int layer=0;layer<(full?2:1);layer++) {
                for(int index=0;index<=70;index++) {
                    double part=index/70.; BOLT.at(written*part,sample,1,0);
                    stroke.add(sample.across+.8*layer,sample.down-.8*layer,layer==0?(.55+.45*Math.sin(Math.PI*part))*(.6+.4*ease(0,.12,part)):edge(part));
                }
                stroke.paint(layer==0?primary:second,layer==0?(full?3:1.95):1,remaining*.99);
            }
        }
        for(int index=0;index<=48;index++) { double part=index/48.; ember(phase-(full?.077:.04)*(1-part)); stroke.add(sample.across,sample.down,tail(part)); }
        stroke.paint(primary,1.8,.91); ember(phase); pearl(sample.across,sample.down,primary,1.16);
        if(full && phase>=.43) {
            double age=(phase-.43)/.57,alpha=ease(0,.12,age)*(1-ease(.74,1,age));
            for(int side=-1;side<=1;side+=2) {
                for(int index=0;index<=24;index++) {
                    double part=index/24.,travel=clamp(age-.095*(1-part)); RETURN.at(travel,sample,1,0);
                    sample.across+=side*9*Math.sin(Math.PI*travel); sample.down+=side*4*Math.sin(TURN*travel);
                    stroke.add(sample.across,sample.down,tail(part));
                }
                stroke.paint(second,1,alpha*.82); disc(sample.across,sample.down,3.6,second,alpha*.82);
            }
        }
    }
}
