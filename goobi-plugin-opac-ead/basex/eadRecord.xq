(: XQuery file to return an ead record :)
module namespace page = 'http://basex.org/examples/web-page';
declare default element namespace "urn:isbn:1-931666-22-9";

declare
  %rest:path("/search/{$identifier}")
  %rest:single
  %rest:GET
function page:getRecord($identifier) {
    let $ead := db:open('CHANGEME')/ead[//c[@level="file"][@id=$identifier]]
    let $record :=$ead//c[@level="file"][@id=$identifier]
    let $header := $ead/eadheader

    return
    <ead>
        {$header}
        {for $c in $record/ancestor-or-self::c
        return
            <c level="{data($c/@level)}" id="{data($c/@id)}">
                {$c/did}
                {$c/accessrestrict}
                {$c/otherfindaid}
                {$c/odd}
                {$c/scopecontent}
                {$c/index}
            </c>
        }
    </ead>
};
