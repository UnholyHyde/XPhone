package ru.xphone.booster;

/** Размеры в dp; запас для шапки, показателей, настроек и нижних кнопок. */
final class PanelLayout {
    final int width, columns, tileHeight;
    PanelLayout(int screenWidth,int screenHeight,int count,boolean glass,float fontScale,boolean locked) {
        boolean landscape=screenWidth>screenHeight;
        int reserve=locked?246:218;
        int minTile=Math.max(76,(int)Math.ceil(42+36*fontScale));
        int available=Math.max(minTile,screenHeight-24-reserve);
        int preferred=landscape?5:3;
        int maxColumns=Math.max(1,Math.min(7,(screenWidth-56)/94));
        int cols=Math.min(preferred,maxColumns);
        while(cols<maxColumns && ((count+cols-1)/cols)*(minTile+6)-6>available) cols++;
        columns=Math.max(1,cols);
        width=Math.min(screenWidth-24,32+columns*94+(columns-1)*8);
        int rows=Math.max(1,(count+columns-1)/columns);
        tileHeight=Math.max(minTile,Math.min(glass?98:88,(available-(rows-1)*6)/rows));
    }
}
