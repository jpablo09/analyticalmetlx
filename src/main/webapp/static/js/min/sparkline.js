var SparkLine=function(){return{svg:function(k,e,f,c,n,p){var g=d3.select(k[0]).append("svg:svg").attr("class","sparkline").attr("width",f).attr("height",c),h=d3.scaleLinear().domain([0,e[0].length]).range([f,0]),d=d3.scaleLinear().domain(d3.extent(e)).range([0,c]),l=d3.line().x(function(a,b){return h(b)}).y(function(a){return c/2-d(a)}).curve(d3.curveBasis),m=d3.line().x(function(a,b){return h(b)}).y(function(a){return d(a)+c/2}).curve(d3.curveBasis);g.selectAll("path").data(e).enter().append("svg:path");
return function(a){a=_.clone(a);var b=_.map(a,function(a){a.unshift(0);a.push(0);return d3.extent(a)});switch(b.length){case 2:d.domain([Math.min(b[0][0],b[1][0]),Math.max(b[0][1],b[1][1])]);break;case 1:d.domain(b[0])}g.selectAll("path").data(a).attr("d",function(a,b){return(0==b?l:m)(a)}).attr("fill",function(a,b){return["green","red"][b]})}}}}();